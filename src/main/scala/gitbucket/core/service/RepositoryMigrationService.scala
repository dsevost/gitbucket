package gitbucket.core.service

import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap

import gitbucket.core.model.Profile.profile.blockingApi._
import gitbucket.core.util.Directory._
import gitbucket.core.util.{FileUtil, JGitUtil, LockUtil}
import gitbucket.core.model.{Account, Repository, Role}
import gitbucket.core.plugin.PluginRegistry
import gitbucket.core.model.Profile._
import gitbucket.core.service.RepositoryService.RepositoryInfo
import gitbucket.core.servlet.Database
import org.apache.commons.io.FileUtils
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.dircache.DirCache
import org.eclipse.jgit.lib.{Constants, FileMode}
import org.slf4j.LoggerFactory

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.jdk.CollectionConverters._
import scala.util.Using

object RepositoryMigrationService {

  private val Creating = new ConcurrentHashMap[String, Option[String]]()

/*  def isCreating(owner: String, repository: String): Boolean = {
    Option(Creating.get(s"${owner}/${repository}")).exists(_.isEmpty)
  }
*/
  def startMigration(owner: String, repository: String): Unit = {
    Creating.put(s"${owner}/${repository}", None)
  }

  def endMigration(owner: String, repository: String, error: Option[String]): Unit = {
    error match {
      case None        => Creating.remove(s"${owner}/${repository}")
      case Some(error) => Creating.put(s"${owner}/${repository}", Some(error))
    }
  }

/*  def getCreationError(owner: String, repository: String): Option[String] = {
    Option(Creating.remove(s"${owner}/${repository}")).flatten
  }
*/
}

trait RepositoryMigrationService {
  self: AccountService
    with RepositoryService
    with LabelsService
    with PrioritiesService =>

  import RepositoryService._

  private val logger = LoggerFactory.getLogger(classOf[RepositoryMigrationService])

/*  def canCreateRepository(repositoryOwner: String, loginAccount: Account)(implicit session: Session): Boolean = {
    repositoryOwner == loginAccount.userName || getGroupsByUserName(loginAccount.userName)
      .contains(repositoryOwner) || loginAccount.isAdmin
  }
*/

  def migrateRepository(
    loginAccount: Account,
    owner: String,
    name: String,
    isPrivate: Boolean,
    sourceUrl: String
  ): Future[Unit] = Future {

    RepositoryMigrationService.startMigration(owner, name)
    try {
      Database() withTransaction { implicit session =>
        //val ownerAccount = getAccountByUserName(owner).get
        val loginUserName = loginAccount.userName

        logger.warn(s"::migrateRepository($sourceUrl) - create copy repository dir");
        val copyRepositoryDir = {
/*          val branches = Git.lsRemoteRepository().setHeads(true).setTags(true).setRemote(sourceUrl).callAsMap

          branches.forEach {
            case (k, v) =>
              logger.warn(s"Key: $k, Ref: $v}");
          }
*/
          val dir = Files.createTempDirectory(s"gitbucket-${owner}-${name}").toFile
          // Git.cloneRepository().setBare(true).setURI(url).setDirectory(dir).setCloneAllBranches(true).call()
          val cloneGit = Git.cloneRepository().setMirror(true).setURI(sourceUrl).setDirectory(dir).call()
          logger.warn(s"Remote git repository clone dir: ${dir}")

          Some(dir)
        }


        logger.warn(s"::migrateRepository($copyRepositoryDir)");

        // Insert to the database at first
        insertRepository(name, owner, None, isPrivate)

//        insertDefaultLabels(owner, name)
//        insertDefaultPriorities(owner, name)

        // Create the actual repository
        val gitdir = getRepositoryDir(owner, name)
        JGitUtil.initRepository(gitdir)

        copyRepositoryDir.foreach { dir =>
          logger.warn(s"Handling git: ${dir}")
          try {
            Using.resource(Git.open(dir)) { git =>
              git.push().setRemote(gitdir.toURI.toString).setPushAll().setPushTags().call()

              val branchList = git.branchList.call.asScala.map { ref => ref.getName.stripPrefix("refs/heads/") }.toList
              logger.warn(s"BRANCHLIST: $branchList")
              if (!branchList.contains("master")) {
                saveRepositoryDefaultBranch(owner, name, branchList.head)
              }
            }
          } finally {
            FileUtils.deleteQuietly(dir)
          }
        }

        // Call hooks
        PluginRegistry().getRepositoryHooks.foreach(_.created(owner, name))
      }

      RepositoryMigrationService.endMigration(owner, name, None)

    } catch {
      case ex: Exception => RepositoryCreationService.endCreation(owner, name, Some(ex.toString))
    }
  }

  def getOnlyRepository(userName: String, repositoryName: String)(implicit s: Session): Option[Repository] = {
    logger.debug(s"::getOnlyRepository($userName,$repositoryName)")
    (Repositories
      .filter { r => r.byRepository(userName, repositoryName)
    } firstOption) map {
      case(repository) =>
        logger.debug(s"::getOnlyRepository($userName,$repositoryName) = $repository")
        repository
    }
  }

  def updateMirroredRepositoryOptions(
    userName: String,
    repositoryName: String
  )(implicit s: Session): Unit = {
    logger.debug(s"::updateMirroredRepositoryOptions($userName,$repositoryName)")
    Repositories
      .filter(_.byRepository(userName, repositoryName))
      .map { r =>
        (
          r.issuesOption,
          r.wikiOption,
        )
      }
      .update(
        "DISABLE",
        "DISABLE"
      )
  }

}
