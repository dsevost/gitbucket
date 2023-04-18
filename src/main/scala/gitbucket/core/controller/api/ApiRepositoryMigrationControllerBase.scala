package gitbucket.core.controller.api
import gitbucket.core.api._
import gitbucket.core.controller.ControllerBase
import gitbucket.core.service.{AccountService, RepositoryCreationService, RepositoryMigrationService, RepositoryService}
import gitbucket.core.servlet.Database
import gitbucket.core.util._
import gitbucket.core.util.Directory.getRepositoryDir
import gitbucket.core.util.Implicits._
import gitbucket.core.model.Profile.profile.blockingApi._
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Constants
//import org.scalatra.Forbidden
import org.slf4j.LoggerFactory

import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.jdk.CollectionConverters._
import scala.util.Using

trait ApiRepositoryMigrationControllerBase extends ControllerBase {
  self: RepositoryService
    with ApiGitReferenceControllerBase
    with RepositoryCreationService
    with RepositoryMigrationService
    with AccountService
    with OwnerAuthenticator
    with UsersAuthenticator
    with GroupManagerAuthenticator
    with ReferrerAuthenticator
    with ReadableUsersAuthenticator
    with WritableUsersAuthenticator =>

  private val logger = LoggerFactory.getLogger(classOf[ApiRepositoryMigrationControllerBase])

  post("/api/v3/user/migrate")(usersOnly {
    logger.warn(s"::post(migrate)")
    val owner = context.loginAccount.get.userName
    (for {
      data <- extractFromJsonBody[MigrateARepository] if data.isValid
    } yield {
      logger.warn(s"::post(data: migrate) - $data")
      LockUtil.lock(s"${owner}/${data.name}") {
        if (getRepository(owner, data.name).isDefined) {
          ApiError(
            "A repository with this name already exists on this account",
            Some("https://developer.github.com/v3/repos/#create")
          )
        } else {
          val f = migrateRepository(
            context.loginAccount.get,
            owner,
            data.name,
            data.`private`,
            data.mirror_of
          )
          Await.result(f, Duration.Inf)
          updateMirroredRepositoryOptions(owner, data.name)

          val r = getRepository(owner, data.name)
          logger.warn(s"::post(reponame: migrate) - $r")

          flash.update("info", "Repository settings has been updated.")

          Using.resource(Git.open(getRepositoryDir(owner, data.name))) {
            git =>
              val branchList = git.branchList.call.asScala.map { ref =>
                ref.getName.stripPrefix("refs/heads/")
              }.toList
              logger.warn(s"BRANCHLIST: $branchList")
              if (!branchList.contains("master")) {
                git.getRepository.updateRef(Constants.HEAD, true).link(Constants.R_HEADS + "main")
              }
//            git.getRepository.updateRef(Constants.HEAD, true).link(Constants.R_HEADS + "main")
//            saveRepositoryDefaultBranch(owner, data.name, "main")
          }
          flash.update("info", "Repository default branch has been updated.")

          val repository = Database() withTransaction { session =>
            getRepository(owner, data.name)(session).get
          }
          JsonFormat(ApiRepository(repository, ApiUser(getAccountByUserName(owner).get)))
        }
      }
    }) getOrElse NotFound()
  })

  /*
  post("/api/v3/orgs/:org/migrate")(usersOnly {
    val groupName = params("org")
    (for {
      data <- extractFromJsonBody[MigrateARepository] if data.isValid
    } yield {
      LockUtil.lock(s"${groupName}/${data.name}") {
        if (getRepository(groupName, data.name).isDefined) {
          ApiError(
            "A repository with this name already exists for this group",
            Some("https://developer.github.com/v3/repos/#create")
          )
        } else if (!canCreateRepository(groupName, context.loginAccount.get)) {
          Forbidden()
        } else {
          val f = createRepository(
            context.loginAccount.get,
            groupName,
            data.name,
            data.description,
            data.`private`,
            "COPY",
            Option(data.mirror_of)
          )
          Await.result(f, Duration.Inf)
          val repository = Database() withTransaction { session =>
            getRepository(groupName, data.name).get
          }
          JsonFormat(ApiRepository(repository, ApiUser(getAccountByUserName(groupName).get)))
        }
      }
    }) getOrElse NotFound()
  })
   */

  delete("/api/v3/repos/:owner/:repository")(usersOnly {
    val userName = params("owner")
    val repositoryName = params("repository")
    val repository = getOnlyRepository(userName, repositoryName)

    logger.debug(s"::delete() - $userName/$repositoryName : $repository")
    if (repository.isDefined) {
      deleteRepository(repository.get)
      responseCode(204)
    } else {
      logger.info(s"Repository '$userName/$repositoryName' not found")
      responseCode()
    }
  })

  def responseCode(code: Int = 404, message: String = "Not Found"): Unit = {
    halt(code, s"""{ "message": $message }""")
  }
}
