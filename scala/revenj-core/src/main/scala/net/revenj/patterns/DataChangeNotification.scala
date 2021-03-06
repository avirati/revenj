package net.revenj.patterns

import monix.reactive.Observable

import scala.concurrent.Future
import scala.reflect.ClassTag

trait DataChangeNotification {

  def notifications: Observable[DataChangeNotification.NotifyInfo]

  def track[T : ClassTag](implicit manifest: ClassTag[T]): Observable[DataChangeNotification.TrackInfo[T]]

  def reset(): Unit
}

object DataChangeNotification {
  sealed trait Operation
  object Operation {
    case object Insert extends Operation
    case object Update extends Operation
    case object Change extends Operation
    case object Delete extends Operation
  }

  sealed trait Source
  object Source {
    case object Database extends Source
    case object Local extends Source
  }

  class NotifyInfo(val name: String, val operation: Operation, val source: Source, val uris: scala.collection.IndexedSeq[String])
  class NotifyWith[T](name: String, operation: Operation, source: Source, uris: scala.collection.IndexedSeq[String], val info: T)
    extends NotifyInfo(name, operation, source, uris)
  object NotifyInfo {
    def apply(name: String, operation: Operation, source: Source, uris: scala.collection.IndexedSeq[String]): NotifyInfo = {
      new NotifyInfo(name, operation, source, uris)
    }
    def apply[T <: Identifiable](name: String, operation: Operation, objects: scala.collection.Seq[T]): NotifyWith[scala.collection.Seq[T]] = {
      new NotifyWith(name, operation, DataChangeNotification.Source.Local, objects.map(_.URI).toIndexedSeq, objects)
    }
  }
  case class TrackInfo[T](uris: scala.collection.IndexedSeq[String], result: Function0[Future[scala.collection.IndexedSeq[T]]])

}
