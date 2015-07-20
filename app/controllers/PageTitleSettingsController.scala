/**
 * Copyright (C) 2012-2013 Kaj Magnus Lindberg (born 1979)
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

package controllers

import actions.ApiActions._
import com.debiki.core._
import com.debiki.core.Prelude._
import com.debiki.core.PageParts.MaxTitleLength
import debiki._
import debiki.DebikiHttp._
import debiki.ReactJson.JsStringOrNull
import play.api._
import play.api.libs.json._
import play.api.mvc.{Action => _, _}
import requests._
import Utils.OkSafeJson


/** Edits the page title and changes settings like forum category, URL path,
  * which layout to use.
  */
object PageTitleSettingsController extends mvc.Controller {


  def editTitleSaveSettings = PostJsonAction(RateLimits.EditPost, maxLength = 2000) {
        request: JsonPostRequest =>

    val pageId = (request.body \ "pageId").as[PageId]
    val newTitle = (request.body \ "newTitle").as[String].trim
    val anyNewParentId = (request.body \ "category").asOpt[PageId]
    val newRoleInt = (request.body \ "pageRole").as[Int]
    val anyLayoutString = (request.body \ "layout").asOpt[String]
    val anyFolder = (request.body \ "folder").asOpt[String] map { folder =>
      if (folder.trim.isEmpty) "/" else folder.trim
    }
    val anySlug = (request.body \ "slug").asOpt[String].map(_.trim)
    val anyShowId = (request.body \ "showId").asOpt[Boolean]

    val newRole = PageRole.fromInt(newRoleInt) getOrElse throwBadArgument("DwE4GU8", "pageRole")

    val hasManuallyEditedSlug = anySlug.exists(slug =>
      slug != ReactRenderer.slugifyTitle(newTitle))

    val oldMeta = request.dao.loadPageMeta(pageId) getOrElse throwNotFound(
      "DwE4KEF20", "The page was deleted just now")

    // Authorization.
    if (!request.theUser.isStaff && request.theUserId != oldMeta.authorId)
      throwForbidden("DwE4KEP2", "You may not rename this page")

    if (anyLayoutString.isDefined || anyFolder.isDefined || hasManuallyEditedSlug ||
        anyShowId.isDefined) {
      if (!request.theUser.isAdmin)
        throwForbidden("DwE5KEP8", o"""Only admins may change the URL path and layout
           and certain other stuff""")
    }

    // SECURITY COULD prevent non-admins from changing the title of pages other than forum topics.
    // (A moderator shouldn't be able to rename the whole forum, or e.g. the about-us page.)

    // Bad request?
    if (anyFolder.exists(!PagePath.isOkayFolder(_)))
      throwBadReq("DwE4KEF23", "Bad folder, must be like: '/some/folder/'")

    if (anySlug.exists(!PagePath.isOkaySlug(_)))
      throwBadReq("DwE6KEF21", "Bad slug, must be like: 'some-page-slug'")

    if (newTitle.length > MaxTitleLength)
      throwBadReq("DwE5kEF2", s"Title too long, max length is $MaxTitleLength")

    // Race condition below: First title committed, then page settings. If the server crashes in
    // between, only the title will be changed — that's fairly okay I think; ignore for now.
    // Also, if two separate requests, then one might edit the title, the other the slug, with
    // weird results. Totally unlikely to happen. Ignore for now.
    //
    // And the lost update bug, when changing path and meta, if two people call this endpoint at
    // exactly the same time. Ignore for now, it's just that all changes won't be saved,
    // but the page will still be in an okay state afterwards.

    // Update page title.
    request.dao.editPost(pageId = pageId, postId = PageParts.TitleId,
      editorId = request.theUser.id, request.theBrowserIdData, newTitle)

    // Update page settings.
    val newMeta = oldMeta.copy(pageRole = newRole, parentPageId = anyNewParentId)
    if (newMeta != oldMeta) {
      request.dao.updatePageMeta(newMeta, old = oldMeta)
    }

    // Update URL path (folder, slug, show/hide page id).
    // The last thing we do, update the url path, so it cannot happen that we change the
    // url path, but then afterwards something else fails so we reply error — that would
    // be bad because the browser wouldn't know if it should update its url path or not.
    var newPath: Option[PagePath] = None
    if (anyFolder.orElse(anySlug).orElse(anyShowId).isDefined) {
      try {
        newPath = Some(
          request.dao.moveRenamePage(
            pageId, newFolder = anyFolder, newSlug = anySlug, showId = anyShowId))
      }
      catch {
        case ex: DbDao.PageNotFoundException =>
          throwNotFound("DwE34FK81", "The page was deleted just now")
        case DbDao.PathClashException(existingPagePath, newPagePath) =>
          throwForbidden(
            "DwE4FKEU5", o"""Cannot move page to ${existingPagePath.value}. There is
              already another page there. Please move that page elsewhere, first""")
      }
    }

    // Refresh cache. If this is a forum category page, we need to refresh the forum and
    // so it'll reload the category list, which is otherwise cached as JSON in the cached HTML.
    // This is a hack. It'll go away when forum categories have their own table? [forumcategory]
    request.dao.refreshPageInAnyCache(pageId)
    if (oldMeta.pageRole == PageRole.Category) {
      val ancestorIds = request.dao.loadAncestorIdsParentFirst(pageId)
      ancestorIds.foreach(request.dao.refreshPageInAnyCache)
    }

    // The browser will update the title and the url path in the address bar.
    OkSafeJson(Json.obj(
      "newTitlePost" -> ReactJson.postToJson2(postId = PageParts.TitleId, pageId = pageId,
          request.dao, includeUnapproved = true),
      "newUrlPath" -> JsStringOrNull(newPath.map(_.value))))
  }

}

