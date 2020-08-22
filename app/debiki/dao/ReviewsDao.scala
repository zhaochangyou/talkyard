/**
 * Copyright (C) 2015 Kaj Magnus Lindberg
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package debiki.dao

import com.debiki.core._
import com.debiki.core.Prelude._
import com.debiki.core.EditedSettings.MaxNumFirstPosts
import debiki.EdHttp._
import java.{util => ju}
import scala.collection.mutable.ArrayBuffer
import scala.collection.{mutable, immutable}
import play.{api => p}
import talkyard.server.dao._


/** Review stuff: a ReviewTask and the users and posts it refers to.
  */
case class ReviewStuff(   // RENAME to  ModTaskStuff
  id: ReviewTaskId,
  reasons: immutable.Seq[ReviewReason],
  createdAt: ju.Date,
  createdBy: Participant,
  moreReasonsAt: Option[ju.Date],
  completedAt: Option[ju.Date],
  decidedBy: Option[Participant],
  invalidatedAt: Option[ju.Date],
  decidedAt: Option[When],
  decision: Option[ReviewDecision],
  maybeBadUser: Participant, // remove? or change to a list, the most recent editors?
  pageId: Option[PageId],
  pageTitle: Option[String],
  post: Option[Post],
  flags: Seq[PostFlag])




trait ReviewsDao {   // RENAME to ModerationDao,  MOVE to  talkyard.server.modn
  self: SiteDao =>


  /** This only remembers a *decision* about what to do. The decision is then
    * carried out, by JanitorActor.executePendingReviewTasks, after a short
    * undo-decision timeout.
    */
  def makeReviewDecisionIfAuthz(taskId: ReviewTaskId, requester: Who, anyRevNr: Option[Int],
        decision: ReviewDecision): Unit = {
    writeTx { (tx, _) =>
      val task = tx.loadReviewTask(taskId) getOrElse throwNotFound(
            "EsE7YMKR25", s"Review task not found, id $taskId")

      throwIfMayNotSeeReviewTaskUseCache(task, requester)

      // Another staff member might have completed this task already, or maybe the current
      // has, but in a different browser tab.
      if (task.doneOrGone)
        return

      // The post might have been moved to a different page, so load it by
      // post id (not original page id and post nr).
      val anyPost = task.postId.flatMap(tx.loadPost)
      val pageId = anyPost.map(_.pageId)

      // This should overwrite any old decision, during the undo timeout,
      // until doneOrGone true above.
      val taskWithDecision = task.copy(
        decidedAt = Some(globals.now().toJavaDate),
        decision = Some(decision),
        decidedById = Some(requester.id),
        decidedAtRevNr = anyRevNr)

      val auditLogEntry = AuditLogEntry(
        siteId = siteId,
        id = AuditLogEntry.UnassignedId,
        didWhat = AuditLogEntryType.MakeReviewDecision,
        doerId = requester.id,
        doneAt = globals.now().toJavaDate,
        browserIdData = requester.browserIdData,
        pageId = pageId,
        uniquePostId = task.postId,
        postNr = task.postNr)
        // COULD add audit log fields: review decision & task id? (4UWSQ1)

      tx.upsertReviewTask(taskWithDecision)
      tx.insertAuditLogEntry(auditLogEntry)
    }
  }



  def tryUndoReviewDecisionIfAuthz(reviewTaskId: ReviewTaskId, requester: Who): Boolean = {
    writeTx { (tx, _) =>
      val task = tx.loadReviewTask(reviewTaskId) getOrElse throwNotFound(
            "TyE48YM4X7", s"Review task not found, id $reviewTaskId")

      throwIfMayNotSeeReviewTaskUseCache(task, requester)

      if (task.isDone) {
        // There's a race between the human and the undo timeout, that's fine,
        // don't throw any error.
        return false
      }

      if (task.gotInvalidated) {
        // Proceed, undo the decision — will have no effect though.
        // Maybe later some day, mod tasks can become active again (un-invalidated).
      }

      throwBadRequestIf(task.decidedAt.isEmpty,
        "TyE5GKQRT2", s"Review action not decided. Task id $reviewTaskId")

      // The post might have been moved to a different page, so reload it.
      val anyPost = task.postId.flatMap(tx.loadPost)
      val pageId = anyPost.map(_.pageId)

      val taskUndone = task.copy(
        decidedAt = None,
        decidedById = None,
        decidedAtRevNr = None,
        decision = None)

      val auditLogEntry = AuditLogEntry(
        siteId = siteId,
        id = AuditLogEntry.UnassignedId,
        didWhat = AuditLogEntryType.UndoReviewDecision,
        doerId = requester.id,
        doneAt = globals.now().toJavaDate,
        browserIdData = requester.browserIdData,
        pageId = pageId,
        uniquePostId = task.postId,
        postNr = task.postNr)
        // COULD add audit log fields: review decision & task id? (4UWSQ1)

      tx.upsertReviewTask(taskUndone)
      tx.insertAuditLogEntry(auditLogEntry)
      true
    }
  }



  def carryOutReviewDecision(taskId: ReviewTaskId): Unit = {
    val pageIdsToRefresh = mutable.Set[PageId]()  ; REMOVE ; CLEAN_UP // use [staleStuff]

    writeTx { (tx, staleStuff) =>
      val anyTask = tx.loadReviewTask(taskId)
      // Not found here is a server bug — we're not handling an end user request.
      val task = anyTask.getOrDie("EsE8YM42", s"s$siteId: Review task $taskId not found")
      task.pageId.map(pageIdsToRefresh.add)

      if (task.gotInvalidated) {
        // This can happen if many users flag a post, and one or different moderators click Delete,
        // for each flag. Then many delete decisions get enqueued, for the same post
        // — and when the first delete decision gets carried out, the other review tasks
        // become invalidated (because now the post is gone). [2MFFKR0]
        // That's fine, just do nothing. (2KYF5A)
        return
      }

      if (task.isDone) {
        // Fine, need do nothing.
        // (There's a race: 1) The janitor actor completes review tasks, [5YMBWQT]
        // and 2) there're in page instant Approve and Reject buttons [in_pg_apr])
        return
      }

      val decision = task.decision getOrDie "TyE4ZK5QL"
      val decidedById = task.decidedById getOrDie "TyE2A2PUM01"
      dieIf(task.decidedAtRevNr.isEmpty, "TyE2A2PUM02")

      dieIf(task.postNr.isEmpty, "Only posts can be reviewed right now [EsE7YGK29]")

      val post = tx.loadPost(task.postId getOrDie "EsE5YGK02") getOrElse {
          logger.warn(s"s$siteId: Review task $taskId: Post ${task.postId} gone, why? [TyE5KQIBQ2]")
          return
        }
      doModTaskNow(post, Seq(task), decision, decidedById = decidedById
              )(tx, staleStuff, pageIdsToRefresh)

      refreshPagesInAnyCache(pageIdsToRefresh)
    }
  }



  /** When on a page, and looking at / reading the post — then, if one
    * approves it, that happens instantly, no undo timeout.
    * Because here one needs to click twice (once to confirm),
    * but on the Moderation staff page, there's no Confirm (that'd been
    * annoying? Because then one might confirms so many things
    * quickly in a row. Then, Undo better?)
    */
  def moderatePostInstantly(postId: PostId, postRevNr: PostRevNr,
          decision: ReviewDecision, moderator: Participant): ModResult = {

    TESTS_MISSING
    dieIf(!moderator.isStaff, "TyE5KRDL356")
    // More authz in the tx below.

    dieIf(decision != ReviewDecision.Accept
          && decision != ReviewDecision.DeletePostOrPage, "TYE06SAKHJ34",
          s"Unexpected moderatePostInstantly() decision: $decision")

    val modResult = writeTx { (tx, staleStuff) =>
      val post = tx.loadPost(postId).get
      throwIfMayNotSeePost(post, Some(moderator))(tx: SiteTransaction)

      val allTasks = tx.loadReviewTasksAboutPostIds(Seq(post.id))
      val tasksToDecide = allTasks.filter(task =>
            !task.doneOrGone &&
            // COULD skip this decision, if it's one's own, and
            // task.postRevNr > postRevNr here.
            // And use the other already decided task (with isDecided true)
            // instead. [skip_decided_mod_tasks]
            !task.isDecidedButNotBy(moderator.id) &&
            // It's for a revision of the post this staff member has seen.
            task.createdAtRevNr.exists(_ <= post.currentRevisionNr))

      throwForbiddenIf(tasksToDecide.isEmpty, "TyE06RKDHN24",
            // (BUG UX harmless: This message is wrong, if
            // task.createdAtRevNr < ... above.)
            "This mod task already decided, maybe by someone else?")

      doModTaskNow(post, tasksToDecide, decision, decidedById = moderator.id
            )(tx, staleStuff,
              mutable.Set.empty) // REMOVE use [staleStuff] instead
    }

    modResult
  }



  /** Implicitly accepts posts as okay, by interacting with them, e.g. replying or
    * editing them. So mods won't need to both read and reply, and also
    * read again and mark as ok in the admin area.  [appr_by_interact]
    *
    * This happens only if the post has been (auto) approved already.
    * Approving-before-publish needs to be done explicitly — see
    * [[moderatePostInstantly()]] above.
    *
    * @param decision should be type:  InteractReply.type | InteractEdit.type
    *                  but won't work until [Scala_3].
    */
  def maybeReviewAcceptPostByInteracting(post: Post, moderator: Participant,
          decision: ReviewDecision)(tx: SiteTx, staleStuff: StaleStuff): Unit = {

    TESTS_MISSING
    dieIf(!moderator.isStaff, "TyE5KRDL356")

    // Skip not-yet-approved posts. Approve-before is more explicit — an
    // in page button to click. [in_pg_apr]
    if (!post.isSomeVersionApproved)
      return

    // Skip access control — we're interacting with the post already,
    // checks done already. And we do Not return and show any modified post
    // from here. (025AKDL)
    // throwIfMayNotSeePost(task, requester)  <—— not needed

    dieIf(decision != ReviewDecision.InteractEdit
          && decision != ReviewDecision.InteractReply, "TyE50RKT25M",
          s"Unsupported maybeAcceptPostByInteracting decision: $decision")

    val allTasks = tx.loadReviewTasksAboutPostIds(Seq(post.id))
    val tasksToAccept = allTasks.filter(task =>
          !task.doneOrGone &&
          // If another mod (or this moderator henself) just did a mod task
          // decision via the moderation page, then, don't change that decision.
          // (Happens if this interaction happens within the mod task undo timeout.)
          // [skip_decided_mod_tasks]
          !task.isDecided &&
          // If other people in the forum reacted to this post, don't accept it
          // implicitly here? Instead, leave the mod task for more explicit
          // consideration on the Moderation page.
          !task.reasons.exists(_.isUnpopular) &&
          // Skip mod tasks about post revisions the staff member hasn't
          // seen (e.g. a just about now edited and flagged new revision).
          // Maybe this has no effect — seems the calle loads the most recent
          // version of `post` always. Oh well. Barely matters.
          task.createdAtRevNr.exists(_ <= post.currentRevisionNr))

    reviewAccceptPost(post, tasksToAccept, decision, decidedById = moderator.id
          )(tx, staleStuff)

    /* rm this comment
    tasksToAccept foreach { task =>
      if (task.decision.isDefined || task.decidedById.isDefined ||
            task.decidedAtRevNr.isDefined || task.decidedAt.isDefined ||
            task.completedAt.isDefined) {
        bugWarn("TyE406RKDN3")
        return
      }

      val now = Some(tx.now.toJavaDate)

      val completedTask = task.copy(
            decision = Some(decision),
            decidedById = Some(staffMember.id),
            decidedAtRevNr = Some(post.currentRevisionNr),
            decidedAt = now,
            completedAt = now)

      // Do the same things as carryOutReviewDecision(() — but only with the
      // mod task, don't change the post itself.
      tx.upsertReviewTask(completedTask)
      updateSpamCheckTasksBecauseReviewDecision(
            humanSaysIsSpam = false, completedTask, tx)
    } */

    AUDIT_LOG // missing — here and most? other fns in this file.

    // Don't return the now review-accepted post. (025AKDL)
  }



  private def doModTaskNow(post: Post, modTasks: Seq[ModTask],
          decision: ModDecision, decidedById: UserId)
          (tx: SiteTx, staleStuff: StaleStuff,
              pageIdsToRefresh: mutable.Set[PageId])
          : ModResult = {

    modTasks foreach { t =>
      dieIf(t.doneOrGone, "TyE50WKDL45", s"Mod task done or gone: $t")
      dieIf(t.decision isSomethingButNot decision, "TyE305RKDHB",
            s"Mod task decision != $decision, task: $t")
      dieIf(t.postId isNot post.id, "TyE7KT35T64",
            s"Mod task post id != post.id, task: $t")
    }

        /*
        // Currently review tasks don't always get invalidated, when posts and pages get deleted. (2KYF5A)
        if (post.deletedAt.isDefined) {
          BUG; SHOULD // update the task anyway! Otherwise if post deleted,
          // there'll be some un-handleable mod tasks!

          // Any spam check task should have been updated already, here: [UPDSPTSK].
          return ModResult.NothingChanged
        } */

        // Remove? Only do if hidden or not yet approved? -----
        pageIdsToRefresh.add(post.pageId) ; REMOVE  // [staleStuff]
        staleStuff.addPageId(post.pageId)
        // ----------------------------------------------------

        /*  rm this
        val newTitleApproved = post.isTitle && !post.isCurrentVersionApproved
                // or?  !post.approvedRevisionNr.exists(_ >= decidedAtRevNr)
        staleStuff.addPageId(post.pageId, backlinksStale = newTitleApproved)

        // Can this happen?
        task.pageId foreach { id =>
          if (id != post.pageId) {
            staleStuff.addPageId(id)
          }
        } */

        // We're in a background thread and have forgotten the browser id data.
        // Could to load it from an earlier audit log entry, ... but maybe it's been deleted?
        // Edit: But now this might as well be called from moderatePostInstantly().
        // Oh well, not important. For now:
        val browserIdData = BrowserIdData.Forgotten

        decision match {
          case ReviewDecision.Accept =>
            if (post.isCurrentVersionApproved) {
              reviewAccceptPost(post, tasksToAccept = modTasks, decision,
                      decidedById = decidedById)(tx, staleStuff)
            }
            else {
              approveAndPublishPost(post, decidedById = decidedById,
                    tasksToAccept = modTasks, pageIdsToRefresh)(tx, staleStuff)
            }
          case ReviewDecision.DeletePostOrPage =>
            rejectAndDeletePost(post, decidedById = decidedById, modTasks, browserIdData
                  )(tx, staleStuff)
          case x =>
            unimpl(s"s$siteId: Cannot handle decision here: $decision [TyE306KD2")
        }
  }



  private def reviewAccceptPost(post: Post, tasksToAccept: Seq[ModTask],
          decision: ModDecision, decidedById: UserId)
          (tx: SiteTx, staleStuff: StaleStuff): ModResult = {

    dieIf(!decision.isFine, "TyE305RKDJW2")
    dieIf(tasksToAccept.exists(_.decision isSomethingButNot decision), "TyE5E03SHP4")

    if (post.isDeleted) {
      // Continue anyway: Unhide the post body (see below), if hidden  [apr_deld_post]
      // — good to do, if the post or page or whatever gets undeleted, later.
      // (Maybe someone else just deleted the post, or deleted via
      // another mod task.)
    }

    val updatedPost =
              // The System user has apparently approved the post already.
              // However, it might have been hidden a tiny bit later, after some  external services
              // said it's spam. Now, though, we know it's apparently not spam, so show it.
              if (post.isBodyHidden) {
                // SPAM RACE COULD unhide only if rev nr that got hidden <=
                // rev that was reviewed. [6GKC3U]

                UX; TESTS_MISSING; BUG // ? will this un-hide the whole page if needed?

                changePostStatusImpl(postNr = post.nr, pageId = post.pageId,
                      PostStatusAction.UnhidePost, userId = decidedById,
                      tx, staleStuff).updatedPost
              }
              else {
                None
              }

    markModTasksCompleted(post, tasksToAccept, decision, decidedById = decidedById
          )(tx, staleStuff)

    ModResult(updatedPost.toSeq, updatedAuthor = None)
  }



  private def approveAndPublishPost(post: Post, decidedById: UserId,
        tasksToAccept: Seq[ModTask], pageIdsToRefresh: mutable.Set[PageId])
        (tx: SiteTx, staleStuff: StaleStuff): ModResult = {

    dieIf(tasksToAccept.isEmpty, "TyE306RKTD5")

    val taskIsForBothTitleAndBody = tasksToAccept.head.isForBothTitleAndBody

    dieIf(post.nr == BodyNr && !taskIsForBothTitleAndBody, "TyE603RKJG3")

    dieIf(tasksToAccept.exists(_.postId isNot post.id), "TyE60KJFHE4")
    // But the post nr might be different — if the post got moved elsewhere.

    dieIf(tasksToAccept.tail.exists(_.isForBothTitleAndBody != taskIsForBothTitleAndBody),
        "TyE603AKDHHW26")

    if (post.isDeleted) {
      // Approve the post anyway  [apr_deld_post] — maybe gets undeletde
      // later, then good to remember it got approved.
    }

    val updatedTitle =
              if (taskIsForBothTitleAndBody) {
                // This is for a new page. Approve the *title* here, and the *body* just below.

                // Maybe skip this check? In case a post gets broken out to a new page,
                // or merged into an existing page.
                dieIfAny(tasksToAccept, (t: ReviewTask) => t.postNr.isNot(PageParts.BodyNr),
                      "TyE306RKDH3" ) /*
                dieIf(!task.postNr.contains(PageParts.BodyNr), "EsE5TK0I2")
                */
                approvePostImpl(post.pageId, PageParts.TitleNr, approverId = decidedById,
                      tx, staleStuff).updatedPost
              }
              else {
                // Then need not approve any title.
                None
              }

    val updatedBody =
              approvePostImpl(post.pageId, post.nr, approverId = decidedById, tx, staleStuff)
                  .updatedPost

    if (!post.isDeleted) {
      perhapsCascadeApproval(post.createdById, pageIdsToRefresh)(tx, staleStuff)
    }

    markModTasksCompleted(post, tasksToAccept, ReviewDecision.Accept,
          decidedById = decidedById)(tx, staleStuff)

    ModResult(updatedBody.toSeq ++ updatedTitle, updatedAuthor = None)
  }



  private def rejectAndDeletePost(post: Post, decidedById: UserId, modTasks: Seq[ModTask],
        browserIdData: BrowserIdData)
        (tx: SiteTx, staleStuff: StaleStuff): ModResult = {

    // For now:
    val taskIsForBothTitleAndBody = modTasks.head.isForBothTitleAndBody
    dieIf(modTasks.exists(_.postId isSomethingButNot post.id), "TyE50WKDL6")

    // Maybe got moved to an new page?
    val pageId = post.pageId

    val (updatedPosts, updatedPageId)  =
            if (post.isDeleted) {
              // Fine, just update the mod tasks. [apr_deld_post]
              // (There're races: mods reviewing and deleting, and others maybe
              // deleting the post or ancestor posts — fine.)
              (Nil, None)
            }
            // If staff deletes many posts by this user, mark it as a moderate threat?
            // That'll be done from inside update-because-deleted fn below. [DETCTHR]
            else if (taskIsForBothTitleAndBody) {
              deletePagesImpl(Seq(pageId), deleterId = decidedById,
                    browserIdData,
                    )(tx, staleStuff)
              // Posts not individually deleted, instead, whole page gone
              (Seq.empty, Some(pageId))
            }
            else {
              val updPost =
                    deletePostImpl(post.pageId, postNr = post.nr, deletedById = decidedById,
                        browserIdData, tx, staleStuff)
                      .updatedPost

              // It's annoying if [other review tasks for the same post] would
              // need to be handled too.
              UX; COULD // do this also if deleting the whole page? (see above)
              invalidateReviewTasksForPosts(Seq(post), doingTasksNow = modTasks, tx)

              (updPost.toSeq, None)
            }
            // Need not:
            // updateSpamCheckTasksBecauseReviewDecision(humanSaysIsSpam = true, task, tx)
            // — that's done from the delete functions already. [UPDSPTSK]

    markModTasksCompleted(post, modTasks, ReviewDecision.DeletePostOrPage,
          decidedById = decidedById)(tx, staleStuff)

    ModResult(updatedPosts, updatedAuthor = None, updatedPageId)
  }



  private def markModTasksCompleted(post: Post, tasks: Seq[ModTask],
        decision: ModDecision, decidedById: UserId)
        (tx: SiteTx, staleStuff: StaleStuff): Unit = {

    val tasksToUpdate = tasks.filter(!_.doneOrGone)

    tasksToUpdate foreach { task =>
      if (bugWarnIf(task.decision isSomethingButNot decision, "TyE50KSD2")) return
      if (bugWarnIf(task.postId isNot post.id, "TyE06FKSD2")) return
      if (bugWarnIf(task.doneOrGone, "TyE7KTJ25")) return

      val now = Some(tx.now.toJavaDate)

      val completedTask =
            if (task.isDecided) task.copy(completedAt = now)
            else task.copy(
                  decision = Some(decision),
                  decidedById = Some(decidedById),
                  decidedAtRevNr = Some(post.currentRevisionNr),
                  decidedAt = now,
                  completedAt = now)

      tx.upsertReviewTask(completedTask)

      if (decision.isFine) {
        updateSpamCheckTasksBecauseReviewDecision(
              humanSaysIsSpam = false, completedTask, tx)
      }
    }
  }



  private def updateSpamCheckTasksBecauseReviewDecision(humanSaysIsSpam: Boolean,
      reviewTask: ReviewTask, tx: SiteTransaction): Unit = {

    val decidedAtRevNr = reviewTask.decidedAtRevNr getOrDie "TyE60ZF2R"
    val postId = reviewTask.postId getOrElse {
      return
    }

    val spamCheckTasksAnyRevNr: Seq[SpamCheckTask] =
      tx.loadSpamCheckTasksWaitingForHumanLatestLast(postId)

    // Which spam check task shall we update? (WHICHTASK) There might be many,
    // for different revisions of the same post (because edits are spam checked, too).
    // Probably the most recent spam check task corresponds to the post revision
    // the reviewer reviewed?
    val latestTask = spamCheckTasksAnyRevNr.lastOption

    // Alternatively — but I'm not sure the rev nrs will match correctly:
    /* val spamCheckTasksSameRevNr =
      spamCheckTasksAnyRevNr.filter(
        _.postToSpamCheck.getOrDie("TyE20597W").postRevNr == decidedAtRevNr)  */

    // How do we know the spam was really inserted in this post revision? What if this is
    // a wiki post, and a previous editor inserted the spam? Ignore, for now. [WIKISPAM]

    latestTask foreach { spamCheckTask =>
      // The Janitor thread will soon take a look at this spam check task, and
      // report classification errors (spam detected, but human says isn't spam, or vice versa)
      // to spam check services. [SPMSCLRPT]
      tx.updateSpamCheckTaskForPostWithResults(
        spamCheckTask.copy(humanSaysIsSpam = Some(humanSaysIsSpam)))
    }
  }


  def updateSpamCheckTaskBecausePostDeleted(post: Post, postAuthor: Participant, deleter: Participant,
        tx: SiteTransaction): Unit = {
    // [DELSPAM] Would be good with a Delete button that asks the deleter if hen
    // deletes the post because hen considers it spam — or for some other reason.
    // So we know for sure if we should mark the post as spam here, and maybe
    // send a yes-this-is-spam training sample to the spam check services.
    //
    // For now: If this post was detected as spam, and it's being deleted by
    // *staff*, assume it's spam.
    //
    // (Tricky tricky: Looking at the post author won't work, for wiki posts, if
    // a user other than the author, edited the wiki post and inserted spam.
    // Then, should instead compare with the last editor (or all/recent editors).
    // But how do we know if the one who inserted any spam, is the last? last but
    // one? two? editor, or the original author? Ignore this, for now. [WIKISPAM])
    //
    // [DETCTHR] If the post got deleted because it's spam, should eventually
    // mark the user as a moderate threat and block hen? Or should the Delete
    // button ask the reviewer "Do you want to ban this user?" instead
    // of automatically? Maybe both: Automatically identify spammers, and
    // ask the deleter to approve the computer's ban suggestion?

    val maybeDeletingSpam = deleter.isStaff && !postAuthor.isStaff && deleter.id != postAuthor.id
    if (!maybeDeletingSpam)  //  || !anyDeleteReason is DeleteReasons.IsSpam) {
      return

    // Which spam check task(s) shall we update? If there're many, for different
    // revision of the same post? The last one? see: (WHICHTASK)
    val spamCheckTasksAnyRevNr = tx.loadSpamCheckTasksWaitingForHumanLatestLast(post.id)
    val latestTask = spamCheckTasksAnyRevNr.lastOption
    /* Alternatively:
    val spamCheckTaskSameRevNr =
      spamCheckTasksAnyRevNr.filter(
        post.approvedRevisionNr.getOrElse(post.currentRevisionNr) ==
          _.postToSpamCheck.getOrDie("TyE529KMW").postRevNr) */

    latestTask foreach { task =>
      tx.updateSpamCheckTaskForPostWithResults(
        task.copy(humanSaysIsSpam = Some(true)))
    }
  }


  CLEAN_UP; REMOVE // this is too complicated, slightly bug prone!
  /** If we have approved all the required first post review tasks caused by userId, then
    * this method auto-approves all remaining first review tasks — because now we trust
    * the user that much.
    *
    */
  @deprecated("remove this, too complicated") // more nice to approve by interacting? [appr_by_interact]
  private def perhapsCascadeApproval(userId: UserId, pageIdsToRefresh: mutable.Set[PageId])(
        tx: SiteTransaction, staleStuff: StaleStuff): Unit = {
    val settings = loadWholeSiteSettings(tx)
    val numFirstToAllow = math.min(MaxNumFirstPosts, settings.maxPostsPendApprBefore)
    val numFirstToApprove = math.min(MaxNumFirstPosts, settings.numFirstPostsToApprove)
    if (numFirstToAllow > 0 && numFirstToApprove > 0) {
      // Load some more review tasks than just MaxNumFirstPosts, in case the user has
      // somehow triggered even more review tasks, e.g. because getting flagged.
      // SECURITY (minor) if other users flag userId's posts 9999 times, we won't load any
      // approved posts here, and the approval won't be cascaded.
      val someMore = 15
      // COULD load tasks for posts, and tasks for approved posts, and tasks resolved as harmful,
      // in three separate queries? So won't risk 9999 of one type —> finds no other types.
      val tasks = tx.loadReviewTasksAboutUser(userId,
        limit = MaxNumFirstPosts + someMore, OrderBy.OldestFirst)

      // Use a set, because there might be many review tasks for the same post, if different
      // people flag the same post.
      var postIdsApproved = Set[PostId]()
      var numHarmful = 0
      tasks foreach { task =>
        if (task.decision.exists(_.isFine)) {
          if (task.postId.isDefined) {
            postIdsApproved += task.postId getOrDie "EdE7KW02Y"
          }
          else {
            // What's this? Perhaps the user editing his/her bio and the bio getting
            // reviewed (not yet implemented though). Ignore.
          }
        }
        else if (task.decision.exists(_.isRejectionBadUser)) {
          numHarmful += 1
        }
      }

      val numApproved = postIdsApproved.size
      if (numHarmful > 0)
        return

      // Don't auto approve users with too low trust level.
      // (Also sometimes causes an e2e test to fail.  TyT305RKDJ26)
      val user = tx.loadParticipant(userId) getOrElse {
        return // hard deleted? Weird
      }
      if (user.effectiveTrustLevel.isAtMost(settings.requireApprovalIfTrustLte))
        return

      val shallApproveRemainingFirstPosts = numApproved >= numFirstToApprove
      if (shallApproveRemainingFirstPosts) {
        val pendingTasks = tasks.filter(!_.doneOrGone)
        val titlesToApprove = mutable.HashSet[PageId]()
        val postIdsToApprove = pendingTasks flatMap { task =>
          if (task.postNr.contains(PageParts.BodyNr)) {
            titlesToApprove += task.pageId getOrDie "EdE2WK0L6"
          }
          task.postId
        }
        val postsToApprove = tx.loadPostsByUniqueId(postIdsToApprove).values
        val titlePostsToApprove = titlesToApprove.flatMap(tx.loadTitle)
        val tooManyPostsToApprove = postsToApprove ++ titlePostsToApprove

        // Some posts might have been approved already — e.g. chat messages; they're
        // auto approved by the System user. [7YKU24]
        val allPostsToApprove = tooManyPostsToApprove.filter(!_.isSomeVersionApproved)

        for ((pageId, posts) <- allPostsToApprove.groupBy(_.pageId)) {
          pageIdsToRefresh += pageId
          autoApprovePendingEarlyPosts(pageId, posts)(tx, staleStuff)
        }
      }
    }
  }


  private def invalidateReviewTasksForPosts(posts: Iterable[Post], doingTasksNow: Seq[ModTask],
        tx: SiteTransaction): Unit = {
    invalidatedReviewTasksImpl(posts, shallBeInvalidated = true, doingTasksNow, tx)
  }


  private def reactivateReviewTasksForPosts(posts: Iterable[Post], doingTasksNow: Seq[ModTask],
         tx: SiteTransaction): Unit = {
    TESTS_MISSING // [UNDELPOST]
    untestedIf(posts.nonEmpty, "TyE2KIFW4", "Reactivating review tasks for undeleted posts") // [2VSP5Q8]
    invalidatedReviewTasksImpl(posts, shallBeInvalidated = false, doingTasksNow, tx)
  }


  /*  [deld_post_mod_tasks]
  After rethinking reviews, maybe better to never
  invalidate any reveiw tasks, when a page / post gets deleted, via *not* the review interface?
  So staff will see everything that gets flagged — even if someone deleted it first
  for whatever reason.

  def invalidateReviewTasksForPageId(pageId: PageId, doingReviewTask: Option[ReviewTask],
         tx: SiteTransaction) {
    val posts = tx.loadPostsOnPage(pageId)
    invalidatedReviewTasksImpl(posts, shallBeInvalidated = true, doingReviewTask, tx)
  }


  def reactivateReviewTasksForPageId(pageId: PageId, doingReviewTask: Option[ReviewTask],
         tx: SiteTransaction) {
    val posts = tx.loadPostsOnPage(pageId)
    invalidatedReviewTasksImpl(posts, shallBeInvalidated = false, doingReviewTask, tx)
  } */


  private def invalidatedReviewTasksImpl(posts: Iterable[Post], shallBeInvalidated: Boolean,
        doingTasksNow: Seq[ModTask], tx: SiteTransaction): Unit = {

    // If bug then:
    // If somehow some day a review task doesn't get properly invalidated, and
    // it also cannot be decided & completed: Fix the bug, & delete that row from the database,
    // maybe even delete all review tasks, they are relatively unimportant, & no incoming keys.

    val now = globals.now().toJavaDate
    val tasksLoaded = tx.loadReviewTasksAboutPostIds(posts.map(_.id))
    def isReactivating = !shallBeInvalidated  // easier to read

    val tasksToUpdate = tasksLoaded filterNot { task =>
      def anyPostForThisTask = task.postId.flatMap(taskPostId => posts.find(_.id == taskPostId))
      def postDeleted = anyPostForThisTask.exists(_.isDeleted)
      (task.completedAt.isDefined
        || task.invalidatedAt.isDefined == shallBeInvalidated  // already correct status
        || doingTasksNow.exists(_.id == task.id)  // this task gets updated by some ancestor caller
        || (isReactivating && postDeleted))  // if post gone, don't reactivate this task
    }

    val tasksAfter = tasksToUpdate.map { task =>
      task.copy(invalidatedAt = if (shallBeInvalidated) Some(now) else None)
    }

    tasksAfter.foreach(tx.upsertReviewTask)
  }


  def loadReviewStuff(olderOrEqualTo: Option[ju.Date], limit: Int, forWho: Who)
        : (Seq[ReviewStuff], ReviewTaskCounts, Map[UserId, Participant], Map[PageId, PageMeta]) =
    readOnlyTransaction { tx =>
      val requester = tx.loadTheParticipant(forWho.id)
      loadStuffImpl(olderOrEqualTo, limit, requester, tx)
    }


  private def loadStuffImpl(olderOrEqualTo: Option[ju.Date], limit: Int,
        requester: Participant, tx: SiteTransaction)
        : (Seq[ReviewStuff], ReviewTaskCounts, Map[UserId, Participant], Map[PageId, PageMeta]) = {
    val reviewTasksMaybeNotSee = tx.loadReviewTasks(olderOrEqualTo, limit)
    val taskCounts = tx.loadReviewTaskCounts(requester.isAdmin)

    val postIds = reviewTasksMaybeNotSee.flatMap(_.postId).toSet
    val postsById = tx.loadPostsByUniqueId(postIds)

    val pageIds = postsById.values.map(_.pageId)
    val pageMetaById = tx.loadPageMetasAsMap(pageIds)
    val forbiddenPageIds = mutable.Set[PageId]()

    // ----- May see review task & page?  [TyT5WB2R0] [5FSLW20]

    // Might as well use the cache here (5DE4A28), why not? Otherwise, if listing
    // many tasks, this filter step would maybe take a little bit rather long?

    val reviewTasks = if (requester.isAdmin) reviewTasksMaybeNotSee else {
      val authzContext = getForumAuthzContext(Some(requester)) // (5DE4A28)

      for (pageMeta <- pageMetaById.values) {
        val (maySee, debugCode) = maySeePageUseCacheAndAuthzCtx(pageMeta, authzContext) // (5DE4A28)
        if (!maySee) {
          forbiddenPageIds.add(pageMeta.pageId)
        }
      }

      // Staff may see all posts on a page they may see [5I8QS2A], so we check page access only.
      reviewTasksMaybeNotSee filter { task =>
        task.postId match {
          case None => true
          case Some(postId) =>
            postsById.get(postId) match {
              case None => false
              case Some(post) =>
                !forbiddenPageIds.contains(post.pageId)
            }
        }
      }
    }

    // -----  Load related things: flags, users, pages

    val userIds = mutable.Set[UserId]()
    reviewTasks foreach { task =>
      userIds.add(task.createdById)
      task.decidedById.foreach(userIds.add)
      userIds.add(task.maybeBadUserId)
    }
    postsById.values foreach { post =>
      userIds.add(post.createdById)
      userIds.add(post.currentRevisionById)
      post.lastApprovedEditById.foreach(userIds.add)
    }

    val flags: Seq[PostFlag] = tx.loadFlagsFor(postsById.values.map(_.pagePostNr))
    val flagsByPostId: Map[PostId, Seq[PostFlag]] = flags.groupBy(_.uniqueId)
    flags foreach { flag =>
      userIds.add(flag.flaggerId)
    }

    val usersById = tx.loadParticipantsAsMap(userIds)

    val titlesByPageId = tx.loadTitlesPreferApproved(pageIds)

    // -----  Construct a ReviewStuff list

    val result = ArrayBuffer[ReviewStuff]()
    for (task <- reviewTasks) {
      def whichTask = s"site $siteId, review task id ${task.id}"
      val anyPost = task.postId.flatMap(postsById.get)
      val anyPageTitle = anyPost.flatMap(post => titlesByPageId.get(post.pageId))
      val flags = task.postId match {
        case None => Nil
        case Some(id) => flagsByPostId.getOrElse(id, Nil)
      }
      result.append(
        ReviewStuff(
          id = task.id,
          reasons = task.reasons,
          createdBy = usersById.get(task.createdById) getOrDie "EsE4GUP2",
          createdAt = task.createdAt,
          moreReasonsAt = task.moreReasonsAt,
          completedAt = task.completedAt,
          decidedBy = task.decidedById.flatMap(usersById.get),
          invalidatedAt = task.invalidatedAt,
          decidedAt = When.fromOptDate(task.decidedAt),
          decision = task.decision,
          maybeBadUser = usersById.get(task.maybeBadUserId) getOrDie "EdE2KU8B",
          pageId = task.pageId,
          pageTitle = anyPageTitle,
          post = anyPost,
          flags = flags))
    }
    (result.toSeq, taskCounts, usersById, pageMetaById)
  }

}

