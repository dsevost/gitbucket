package gitbucket.core.api

/**
 * https://try.gitea.io/api/swagger#/repository/repoMigrate
 * api form
 */
case class MigrateARepository(
  name: String,
  `private`: Boolean = false,
  mirror_of: String,
  is_mirror: Boolean = true
) {
  def isValid: Boolean = {
    name.length <= 100 &&
    name.matches("[a-zA-Z0-9\\-\\+_.]+") &&
    !name.startsWith("_") &&
    !name.startsWith("-") &&
    // [https|git://]               [username@]        github.com / gitbucket            / gitbucket
    mirror_of.matches(
      """^https.*"""
    )
  }
}

    // [https|git://]               [username@]        github.com / gitbucket            / gitbucket
//    mirror_of.matches(
//      "^\\(\\(https\\|git\\)://\\)\\?\\([a-z0-9]\\+@\\)\\?\\([^/]\\+\\)/\\([a-zA-Z0-9_-]\\)\\+/\\([a-zA-Z0-9_-]\\)\\+"
//    )
