/**
 * Copyright (c) 2011 Kaj Magnus Lindberg (born 1979)
 * Created on 2011-09-15.
 */

package com.debiki.v0


case class Tenant(
  id: String,
  name: String,
  hosts: List[TenantHost]
){
  // Reqiure at most 1 canonical host.
  //require((0 /: hosts)(_ + (if (_.isCanonical) 1 else 0)) <= 1)

  def chost_! : TenantHost = hosts.find(_.role == TenantHost.RoleCanonical).get
}

object TenantHost {
  sealed abstract class HttpsInfo

  /** A client that connects over HTTP should be redirected to HTTPS. */
  case object HttpsRequired extends HttpsInfo

  /** When showing a page over HTTPS, <link rel=canonical> should point
   * to the canonical version, which is the HTTP version.
   */
  case object HttpsAllowed extends HttpsInfo

  case object HttpsNone extends HttpsInfo

  sealed abstract class Role
  case object RoleCanonical extends Role
  case object RoleRedirect extends Role
  case object RoleLink extends Role
  case object RoleDuplicate extends Role
}


case class TenantHost(
  address: String,
  role: TenantHost.Role,
  https: TenantHost.HttpsInfo
)


/** The result of looking up a tenant by host name.
  */
sealed abstract class TenantLookup

/** The looked up host is the canonical host for the tenant found.
 */
case class FoundChost(tenantId: String) extends TenantLookup

/** The host is an alias for the canonical host.
  */
case class FoundAlias(
  tenantId: String,

  /** E.g. `http://www.example.com'. */
  canonicalHostUrl: String,

  /** What the server should do with this request. Should id redirect to
   *  the canonical host, or include a <link rel=canonical>?
   */
  role: TenantHost.Role
) extends TenantLookup


/** The server could e.g. reply 404 Not Found.
 */
case object FoundNothing extends TenantLookup

