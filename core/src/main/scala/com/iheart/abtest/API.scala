/*
 * Copyright [2018] [iHeartMedia Inc]
 * All rights reserved
 */

package com.iheart
package abtest

import java.time.OffsetDateTime

import Error._
import model._
import lihua.mongo._
import EntityDAO.Query
import reactivemongo.api.commands.GetLastError
import cats.implicits._
import mouse.all._
import cats._
import cats.data.EitherT
import cats.effect.IO
import com.iheart.abtest.persistence._
import com.typesafe.config.Config
import mtl.implicits._
import monocle.macros.syntax.lens._
import play.api.libs.json.{JsArray, JsString, Json}
import henkan.convert.Syntax._
import lihua.crypt.CryptTsec
import mainecoon.{autoFunctorK, finalAlg}

import concurrent.duration._
import scala.concurrent.ExecutionContext
import scala.util.Random
/**
 * Algebra for ABT API
 * Final Tagless encoding
 * @tparam F
 */

@autoFunctorK(autoDerivation = false) @finalAlg
trait API[F[_]] {

  def create(testSpec: AbtestSpec, auto: Boolean): F[Entity[Abtest]]

  /**
   * Stop a test before it ends
   * @param test
   * @return Some(test) if it already started, None if not started yet.
   */
  def terminate(test: TestId): F[Option[Entity[Abtest]]]

  def getTest(test: TestId): F[Entity[Abtest]]

  def getTestsByFeature(feature: FeatureName): F[Vector[Entity[Abtest]]]

  def continue(spec: AbtestSpec): F[Entity[Abtest]]

  def getAllFeatures: F[List[FeatureName]]

  /**
   * Get all the tests
   * @param time optional time constraint, if set, this will only return tests as of that time.
   */
  def getAllTests(time: Option[OffsetDateTime]): F[Vector[Entity[Abtest]]]

  /**
   * Get all the tests that end after a certain time
   * @param time optional time constraint, if set, this will only return tests as of that time.
   */
  def getAllTestsEndAfter(time: OffsetDateTime): F[Vector[Entity[Abtest]]]

  /**
   * Get all the tests together with their Feature cached.
   * @param time optional time constraint, if set, this will only return tests as of that time.
   */
  def getAllTestsCached(time: Option[OffsetDateTime]): F[Vector[(Entity[Abtest], Feature)]]

  def addOverrides(featureName: FeatureName, overrides: Overrides): F[Feature]

  def getOverrides(featureName: FeatureName): F[Feature]

  def removeOverrides(featureName: FeatureName, userId: UserId): F[Feature]

  /**
   * get all groups of a user by features
   * @param time
   */
  def getGroups(userId: UserId, time: Option[OffsetDateTime], tags: List[Tag]): F[Map[FeatureName, GroupName]]

  def getGroupsWithMeta(query: UserGroupQuery): F[UserGroupQueryResult]

  def addGroupMetas(test: TestId, metas: Map[GroupName, GroupMeta]): F[Entity[AbtestExtras]]

  def getTestExtras(test: TestId): F[Option[Entity[AbtestExtras]]]

  /**
   *
   * Get the assignments for a list of ids bypassing the eligibility control
   */
  def getGroupAssignments(ids: List[String], feature: FeatureName, at: OffsetDateTime): F[List[(String, GroupName)]]
}

object API

import persistence.FeatureDAOEnhancement._
final class DefaultAPI[F[_]](cacheTtl: FiniteDuration)(
  implicit
  val abTestDao:       EntityDAO[F, Abtest],
  val abTestExtrasDao: EntityDAO[F, AbtestExtras],
  val featureDao:      EntityDAO[F, Feature],
  F:                   MonadError[F, Error],
  eligibilityControl:  EligibilityControl[F]
) extends API[F] {
  implicit val wc = GetLastError.Default

  import com.iheart.abtest.persistence.AbtestQuery._

  def create(testSpec: AbtestSpec, auto: Boolean): F[Entity[Abtest]] =
    addTestWithLock(testSpec.feature)(createWithoutLock(testSpec, auto))

  def continue(testSpec: AbtestSpec): F[Entity[Abtest]] = addTestWithLock(testSpec.feature) {
    for {
      _ <- validateForCreation(testSpec)
      continueFrom <- getTestByFeature(testSpec.feature)
      created <- continueWith(testSpec, continueFrom)
    } yield created
  }

  def getAllTestsCached(time: Option[OffsetDateTime]): F[Vector[(Entity[Abtest], Feature)]] = {
    val ofTime = time.getOrElse(TimeUtil.currentMinute)
    for {
      tests <- abTestDao.findCached(byTime(ofTime), cacheTtl)
      features <- featureDao.findCached(
        Json.obj("name" ->
          Json.obj("$in" ->
            JsArray(tests.map(t => JsString(t.data.feature))))), cacheTtl
      )
    } yield tests.map(t =>
      (
        t,
        features.find(_.data.name == t.data.feature).map(_.data)
        .getOrElse(
          Feature(t.data.feature, None, Map())
        )
      ))
  }

  def getAllFeatures: F[List[FeatureName]] =
    abTestDao.find(Json.obj()).map(_.map(_.data.feature).distinct.toList.sorted)

  def getTest(id: TestId): F[Entity[Abtest]] = abTestDao.get(id)

  def getAllTests(time: Option[OffsetDateTime]): F[Vector[Entity[Abtest]]] =
    abTestDao.find(byTime(time.getOrElse(OffsetDateTime.now)))

  def getAllTestsEndAfter(time: OffsetDateTime): F[Vector[Entity[Abtest]]] =
    abTestDao.find(endTimeAfter(time))

  def getTestsByFeature(feature: FeatureName): F[Vector[Entity[Abtest]]] =
    abTestDao.find(Query(Json.obj("feature" -> JsString(feature))))
      .map(_.sortBy(t => (t.data.start, t.data.end.getOrElse(OffsetDateTime.MAX))).reverse)

  def getTestByFeature(feature: FeatureName): F[Entity[Abtest]] =
    getTestsByFeature(feature)
      .ensure(Error.NotFound)(_.nonEmpty)
      .map(_.head)

  def getTestByFeature(feature: FeatureName, at: OffsetDateTime): F[Entity[Abtest]] =
    abTestDao.findOne(Query(Json.obj("feature" -> JsString(feature)) ++ byTime(at)))

  def addOverrides(featureName: FeatureName, overrides: Overrides): F[Feature] = for {
    feature <- ensureFeature(featureName)
    updated <- featureDao.update(feature.lens(_.data.overrides).modify(_ ++ overrides))
  } yield updated.data

  def removeOverrides(featureName: FeatureName, userId: UserId): F[Feature] = for {
    feature <- featureDao.byName(featureName)
    updated <- featureDao.update(feature.lens(_.data.overrides).modify(_ - userId))
  } yield updated.data

  def getOverrides(featureName: FeatureName): F[Feature] =
    featureDao.byName(featureName).map(_.data)

  def getGroups(userId: UserId, time: Option[OffsetDateTime], userTags: List[Tag]): F[Map[FeatureName, GroupName]] =
    getGroupAssignmentsOf(UserGroupQuery(Some(userId), time, userTags))._2.map(toGroups)

  def terminate(testId: TestId): F[Option[Entity[Abtest]]] =
    getTest(testId).flatMap { test =>
      test.data.statusAsOf(OffsetDateTime.now) match {
        case Abtest.Status.Scheduled =>
          abTestDao.remove(testId).as(None)
        case Abtest.Status.InProgress =>
          abTestDao.update(test.lens(_.data.end).set(Some(OffsetDateTime.now))).map(Option.apply)
        case Abtest.Status.Expired =>
          F.pure(Some(test))
      }
    }

  def addGroupMetas(testId: TestId, metas: Map[GroupName, GroupMeta]): F[Entity[AbtestExtras]] =
    for {
      test <- canChange(testId)
      _ <- errorsOToF(metas.keys.toList.map(gn => (!test.data.groups.exists(_.name === gn)).
        option(Error.GroupNameDoesNotExist(gn))))
      existing <- getTestExtras(testId)
      toUpdate = existing.fold(Entity(testId, AbtestExtras(metas)))(
        _.lens(_.data.groupMetas).modify(_ ++ metas)
      )
      r <- abTestExtrasDao.upsert(toUpdate)
    } yield r

  def getTestExtras(testId: TestId): F[Option[Entity[AbtestExtras]]] = {
    abTestExtrasDao.findOneOption(Query.idSelector(testId))
  }

  def getGroupsWithMeta(query: UserGroupQuery): F[UserGroupQueryResult] = {
    val (at, groupAssignmentsF) = getGroupAssignmentsOf(query)
    for {
      groupAssignments <- groupAssignmentsF
      metas <- groupAssignments.toList.traverseFilter {
        case (feature, (groupName, test)) =>
          abTestExtrasDao.findCached(Query.idSelector(test._id), cacheTtl).map { f =>
            f.headOption.flatMap(_.data.groupMetas.get(groupName).map((feature, _)))
          }
      }
    } yield UserGroupQueryResult(at, toGroups(groupAssignments), metas.toMap)

  }

  /**
   * bypassing the eligibility control
   */
  def getGroupAssignments(ids: List[UserId], featureName: FeatureName, at: OffsetDateTime): F[List[(UserId, GroupName)]] =
    for {
      test <- getTestByFeature(featureName, at)
      feature <- featureDao.findOne('name -> featureName)
    } yield (ids.flatMap { uid =>
      Bucketing.getGroup(uid, test.data).map((uid, _))
    }.toMap ++ feature.data.overrides).toList

  private def updateLock(fn: FeatureName, obtain: Boolean): F[Unit] = (for {
    feature <- ensureFeature(fn)
    _ <- featureDao.update(
      Json.obj("name" -> fn, "locked" -> Json.obj("$ne" -> obtain)),
      feature.lens(_.data.locked).set(obtain),
      upsert = false
    )
  } yield ()).adaptError {
    case Error.FailedToPersist(_) | Error.DBException(_: reactivemongo.api.commands.LastError) => Error.ConflictCreation(fn)
  }

  private def createWithoutLock(testSpec: AbtestSpec, auto: Boolean): F[Entity[Abtest]] =
    for {
      _ <- validateForCreation(testSpec)
      created <- if (auto)
        createAuto(testSpec)
      else for {
        lastOne <- lastTest(testSpec)
        r <- lastOne.filter(_.data.endsAfter(testSpec.start)).fold(
          doCreate(testSpec, lastOne)
        )(le => F.raiseError(ConflictTest(le)))
      } yield r

    } yield created

  private def addTestWithLock(fn: FeatureName)(add: F[Entity[Abtest]]): F[Entity[Abtest]] = for {
    _ <- updateLock(fn, true)
    attempt <- F.attempt(add)
    _ <- updateLock(fn, false)
    t <- F.fromEither(attempt)
  } yield t

  private def createAuto(ts: AbtestSpec): F[Entity[Abtest]] = for {
    lastOne <- lastTest(ts)
    r <- lastOne.fold(doCreate(ts, None)) { lte =>
      val lt = lte.data
      lt.statusAsOf(ts.start) match {
        case Abtest.Status.Scheduled =>
          terminate(lte._id) >> createWithoutLock(ts, false)
        case Abtest.Status.InProgress =>
          continueWith(ts, lte)
        case Abtest.Status.Expired =>
          doCreate(ts, lastOne)
      }
    }
  } yield r

  private def continueWith(testSpec: AbtestSpec, continueFrom: Entity[Abtest]): F[Entity[Abtest]] =
    for {
      _ <- errorToF(continueFrom.data.end.filter(_.isBefore(testSpec.start)).map(ContinuationGap(_, testSpec.start)))
      _ <- errorToF(continueFrom.data.start.isAfter(testSpec.start).option(
        ContinuationBefore(continueFrom.data.start, testSpec.start)
      ))
      updatedContinueFrom <- abTestDao.update(continueFrom.lens(_.data.end).set(Some(testSpec.start)))
      created <- doCreate(testSpec, Some(updatedContinueFrom))
    } yield created

  private def toGroups(assignments: Map[FeatureName, (GroupName, Entity[Abtest])]): Map[FeatureName, GroupName] =
    assignments.toList.map { case (k, v) => (k, v._1) }.toMap

  private def canChange(testId: TestId): F[Entity[Abtest]] =
    for {
      test <- abTestDao.get(testId)
      _ <- test.data.start.isBefore(OffsetDateTime.now.plusMinutes(1)).option(CannotToChangePastTest(test.data.start)).fold(F.pure(()))(F.raiseError)
    } yield test

  private def getGroupAssignmentsOf(query: UserGroupQuery): (OffsetDateTime, F[Map[FeatureName, (GroupName, Entity[Abtest])]]) = AssignGroups.fromDB[F](cacheTtl).assign(query)

  private def doCreate(newSpec: AbtestSpec, inheritFrom: Option[Entity[Abtest]]): F[Entity[Abtest]] = {
    for {
      newTest <- abTestDao.insert(newSpec.to[Abtest].set(
        ranges = Bucketing.newRanges(newSpec.groups, inheritFrom.map(_.data.ranges).getOrElse(Map.empty)),
        salt = (if (newSpec.reshuffle) Option(Random.alphanumeric.take(10).mkString) else inheritFrom.flatMap(_.data.salt))
      ))
      _ <- inheritFrom.fold(F.unit) { inheritTest =>
        getTestExtras(inheritTest._id).flatMap(_.fold(F.unit) { toCopy =>
          abTestExtrasDao.upsert(toCopy.copy(_id = newTest._id)).void
        })
      }
      _ <- ensureFeature(newSpec.feature)
    } yield newTest
  }

  private def validateForCreation(testSpec: AbtestSpec): F[Unit] =
    List(
      (testSpec.groups.isEmpty).option(Error.EmptyGroups),
      (testSpec.groups.map(_.size).sum > 1.000000001).option(Error.InconsistentGroupSizes(testSpec.groups.map(_.size))),
      testSpec.end.filter(_.isBefore(testSpec.start)).as(Error.InconsistentTimeRange),
      (testSpec.start.isBefore(OffsetDateTime.now.minusMinutes(1))).option(Error.CannotScheduleTestBeforeNow),
      (testSpec.groups.map(_.name).distinct.length != testSpec.groups.length).option(Error.DuplicatedGroupName),
      (testSpec.groups.exists(_.name.length >= 256)).option(Error.GroupNameTooLong),
      (!testSpec.feature.matches("[-_.A-Za-z0-9]+")).option(Error.InvalidFeatureName),
      (!testSpec.alternativeIdName.fold(true)(_.matches("[-_.A-Za-z0-9]+"))).option(Error.InvalidAlternativeIdName)
    )

  private def ensureFeature(name: FeatureName): F[Entity[Feature]] =
    featureDao.byName(name).recoverWith {
      case Error.NotFound =>
        featureDao.insert(Feature(name, None, Map()))
    }

  private implicit def errorsToF(possibleErrors: List[ValidationError]): F[Unit] =
    possibleErrors.toNel.map[Error](ValidationErrors).fold(F.pure(()))(F.raiseError)

  private implicit def errorsOToF(possibleErrors: List[Option[ValidationError]]): F[Unit] =
    errorsToF(possibleErrors.flatten)

  private implicit def errorToF(possibleError: Option[ValidationError]): F[Unit] = List(possibleError)

  private def lastTest(testSpec: AbtestSpec): F[Option[Entity[Abtest]]] =
    getTestByFeature(testSpec.feature)
      .map(Option.apply)
      .recover { case NotFound => None }

}

object DefaultAPI {
  type ET[A] = EitherT[IO, Error, A]

  implicit def instance(
    implicit
    shutdownHook: ShutdownHook,
    config:       Config,
    ex:           ExecutionContext
  ): IO[API[ET]] = {
    import net.ceedubs.ficus.Ficus._
    implicit val fk = com.iheart.abtest.persistence.toApiResult[IO]
    import lihua.mongo.EntityDAO.autoDerive._

    MongoDB[IO](
      config,
      config.as[Option[String]]("mongoDB.secret").map(new CryptTsec[IO](_))
    ).flatMap { m =>
        implicit val mongo = m
        for {
          at <- (new AbtestDAOFactory).create
          atex <- (new AbtestExtrasDAOFactory).create
          f <- (new FeatureDAOFactory).create
        } yield {
          implicit val atd = at
          implicit val atexd = atex
          implicit val fd = f
          val ttl = config.as[FiniteDuration]("iheart.abtest.get-groups.ttl")
          new DefaultAPI[ET](ttl)
        }
      }
  }

}
