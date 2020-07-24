package org.thp.thehive.services

import gremlin.scala._
import javax.inject.{Inject, Named, Singleton}
import org.thp.scalligraph.EntitySteps
import org.thp.scalligraph.auth.{AuthContext, Permission}
import org.thp.scalligraph.controllers.FFile
import org.thp.scalligraph.models.{Database, Entity}
import org.thp.scalligraph.query.PropertyUpdater
import org.thp.scalligraph.services._
import org.thp.scalligraph.steps.StepsOps._
import org.thp.scalligraph.steps.{Traversal, TraversalLike, VertexSteps}
import org.thp.thehive.controllers.v1.Conversion._
import org.thp.thehive.models._
import play.api.libs.json.{JsObject, Json}

import scala.collection.JavaConverters._
import scala.util.Try

@Singleton
class LogSrv @Inject() (attachmentSrv: AttachmentSrv, auditSrv: AuditSrv)(implicit @Named("with-thehive-schema") db: Database)
    extends VertexSrv[Log, LogSteps] {
  val taskLogSrv                                                                 = new EdgeSrv[TaskLog, Task, Log]
  val logAttachmentSrv                                                           = new EdgeSrv[LogAttachment, Log, Attachment]
  override def steps(raw: GremlinScala[Vertex])(implicit graph: Graph): LogSteps = new LogSteps(raw)

  def create(log: Log, task: Task with Entity)(implicit graph: Graph, authContext: AuthContext): Try[Log with Entity] =
    for {
      createdLog <- createEntity(log)
      _          <- taskLogSrv.create(TaskLog(), task, createdLog)
      _          <- auditSrv.log.create(createdLog, task, RichLog(createdLog, Nil).toJson)
    } yield createdLog

  def addAttachment(log: Log with Entity, file: FFile)(implicit graph: Graph, authContext: AuthContext): Try[Attachment with Entity] =
    for {
      task       <- get(log).task.getOrFail()
      attachment <- attachmentSrv.create(file)
      _          <- addAttachment(log, attachment)
      _          <- auditSrv.log.update(log, task, Json.obj("attachment" -> attachment.name))
    } yield attachment

  def addAttachment(
      log: Log with Entity,
      attachment: Attachment with Entity
  )(implicit graph: Graph, authContext: AuthContext): Try[Attachment with Entity] =
    for {
      _    <- logAttachmentSrv.create(LogAttachment(), log, attachment)
      task <- get(log).task.getOrFail()
      _    <- auditSrv.log.update(log, task, Json.obj("attachment" -> attachment.name))
    } yield attachment

  def cascadeRemove(log: Log with Entity)(implicit graph: Graph, authContext: AuthContext): Try[Unit] =
    for {
      _    <- get(log).attachments.toIterator.toTry(attachmentSrv.cascadeRemove(_))
      task <- get(log).task.getOrFail()
      _ = get(log._id).remove()
      _ <- auditSrv.log.delete(log, Some(task))
    } yield ()

  override def update(
      steps: LogSteps,
      propertyUpdaters: Seq[PropertyUpdater]
  )(implicit graph: Graph, authContext: AuthContext): Try[(LogSteps, JsObject)] =
    auditSrv.mergeAudits(super.update(steps, propertyUpdaters)) {
      case (logSteps, updatedFields) =>
        for {
          task <- logSteps.newInstance().task.getOrFail()
          log  <- logSteps.getOrFail()
          _    <- auditSrv.log.update(log, task, updatedFields)
        } yield ()
    }
}

@EntitySteps[Log]
class LogSteps(raw: GremlinScala[Vertex])(implicit @Named("with-thehive-schema") db: Database, graph: Graph) extends VertexSteps[Log](raw) {

  def task = new TaskSteps(raw.in("TaskLog"))

  def visible(implicit authContext: AuthContext): LogSteps =
    newInstance(
      raw.filter(
        _.inTo[TaskLog]
          .inTo[ShareTask]
          .inTo[OrganisationShare]
          .has(Key("name") of authContext.organisation)
      )
    )

  def attachments = new AttachmentSteps(raw.outTo[LogAttachment])

  def `case` = new CaseSteps(
    raw
      .inTo[TaskLog]
      .inTo[ShareTask]
      .outTo[ShareCase]
  )

  def can(permission: Permission)(implicit authContext: AuthContext): LogSteps =
    if (authContext.permissions.contains(permission))
      this.filter(
        _.inTo[TaskLog]
          .inTo[ShareTask]
          .filter(_.outTo[ShareProfile].has("permissions", permission))
          .inTo[OrganisationShare]
          .has("name", authContext.organisation)
      )
    else
      this.limit(0)

  override def newInstance(newRaw: GremlinScala[Vertex]): LogSteps = new LogSteps(newRaw)
  override def newInstance(): LogSteps                             = new LogSteps(raw.clone())

  def richLog: Traversal[RichLog, RichLog] =
    this
      .project(
        _.by
          .by(_.attachments.fold)
      )
      .map {
        case (log, attachments) =>
          RichLog(
            log.as[Log],
            attachments.asScala.map(_.as[Attachment])
          )
      }

  def richLogWithCustomRenderer[A](
      entityRenderer: LogSteps => TraversalLike[_, A]
  )(implicit authContext: AuthContext): Traversal[(RichLog, A), (RichLog, A)] =
    this
      .project(
        _.by
          .by(_.attachments.fold)
          .by(entityRenderer)
      )
      .map {
        case (log, attachments, renderedEntity) =>
          RichLog(
            log.as[Log],
            attachments.asScala.map(_.as[Attachment])
          ) -> renderedEntity
      }
}
