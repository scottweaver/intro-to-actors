package scattergather

import akka.actor._
import data.Hotel
import debug.DebugActor


/** This class manages a scatter-gather index node. 
 *  It is responsible for loading, splitting and monitoring the health of its node. 
 */
class NodeManager(val id: String, val db: ActorRef) extends Actor with ActorLogging with DebugActor {
  import NodeManager._
  var current: Option[ActorRef] = None
  var changes = 0;
  val splitter = context.actorOf(Props(new TopicSplitter(db)), "danger-mcgee-" + id)
  val dbHandler = context.actorOf(Props(new DbWorker(id, db)), "database-saver-" + id)
  
  var bufferedMessages = collection.mutable.ArrayBuffer.empty[(Any, ActorRef)]
  // TODO - supervisor strategy for sub-actor...
  
  // TODO - if we don't hav ea current, can we save messages?
  def sendToCurrent(msg: Any, from: ActorRef): Unit = {
    current match {
      case Some(a) => a.tell(msg, from)
      case _ => bufferedMessages append (msg -> from)
    }
  }
  
  def flushPending(): Unit = {
    val tmp = bufferedMessages
    bufferedMessages = collection.mutable.ArrayBuffer.empty
    tmp foreach (sendToCurrent _).tupled
  }
  
  def receive: Receive = debugHandler orElse {
    case q: SearchQuery => 
      // Send down to the child
      // TODO - intercept!
      sendToCurrent(q, sender)
    case add: AddHotel =>
      log.debug(s"Adding hotel: [${add.content.id}] to node[${id}]")
      sendToCurrent(add, sender)
    // Internal Messages
    case SaveTopic(hotels) =>
      // Ensure the current topic data is saved, in a safe fashion that won't affect the
      // running of the search index.
      dbHandler ! SaveTopic(hotels)
    case SaveCategory(topics) =>
      dbHandler ! SaveCategory(topics)
    case BecomeTopic(hotels) =>
      killAndReplace(context.actorOf(Props(new TopicNode(hotels)), s"topic-$id-$changes"))
      changes += 1
    case BecomeCategory(childIds) =>
      val children =
        for(kid <- childIds)
        yield context.actorOf(Props(new NodeManager(kid, db)), s"node-manager-$kid-$changes")
      killAndReplace(context.actorOf(Props(new CategoryNode(children)), s"category-$id-$changes"))
      changes += 1
    case s @ Split(hotels: Seq[Hotel]) =>
      // TODO - figure out how to split...
      log.debug(s"Splitting node[$id]")
      splitter ! TopicSplitter.Split(id, hotels)
      // We continue servicing requests, even as we try to split.
    case Status.Failure(ex) =>
      // Our future has failed, let's issue a better error perhaps?
      log.error(ex, "Failed to load initial state!")
      throw ex
  }
  
  
  // TODO - ping roland about this one.
  override def preStart(): Unit = {
    loadInitialState()
  }
  
  
  // Loads our initial state and send ourselves a "Become" message.
  def loadInitialState(): Unit = {
    import concurrent.Future
    import akka.pattern.pipe
    import context.dispatcher
    import akka.pattern.ask
    import data.db.DbActor._
    import akka.util.Timeout
    import java.util.concurrent.TimeUnit
    implicit val defaultTimeout = Timeout(3, TimeUnit.SECONDS)
    val categoryState: Future[Seq[String]] =
      // We can't use mapTo because it does hard class equivalence, not subclass friendly.
      (db ? GetCategoryTopicNodeIds(id)).asInstanceOf[Future[Seq[String]]]
    val topicState: Future[Seq[data.Hotel]] =
      (db ? GetTopicHotels(id)).mapTo[Seq[data.Hotel]]
     
    val categoryInitMsg = categoryState map { nodeIds =>
      if(nodeIds.isEmpty) sys.error("This is not a category, we cheat and fail the future here")
      log.debug(s"Got Category state [${id}] with nodes [${nodeIds mkString ","}]")
      BecomeCategory(nodeIds)
    }
    val topicInitMsg= topicState map BecomeTopic.apply
    topicState foreach { hotels =>
      log.debug(s"Got TopicState [${id}] [${hotels map (_.id) mkString ", "}]")
    }
    // Feed this future to use when we're ready for it.
    pipe(categoryInitMsg fallbackTo topicInitMsg) to self
  }
  
  def killAndReplace(next: ActorRef): Unit = {
    current foreach (_ ! PoisonPill)
    current = Some(next)
    // FLush messages we were holding on to.
    flushPending()
  }
}
object NodeManager {
  sealed trait InternalMessage
  case class BecomeTopic(hotels: Seq[Hotel]) extends InternalMessage
  case class BecomeCategory(children: Seq[String]) extends InternalMessage
  // Construct a new Category after splitting these hotels. 
  case class Split(hotels: Seq[Hotel]) extends InternalMessage
  // Tells us we need to ensure the current state of the hotel is saved.
  case class SaveTopic(hotels: Seq[Hotel]) extends InternalMessage
  case class SaveCategory(topics: Seq[String]) extends InternalMessage
}

// This class is meant to keep track of all database tasks and ensure the dangerous ones occur,
// as well as reducing the amount of database hits we perform if the search tree is under heavy
// load.  It currently is pretty dumb.
class DbWorker(val id: String, db: ActorRef) extends Actor {
      // TODO - timeout and other insurances that we actually save
      // this should really have Ack and back-off, and possible a timeout to see
      // if we change before actually issuing db work.
  def receive: Receive = {
    case NodeManager.SaveTopic(hotels) =>
      db ! data.db.DbActor.SaveTopic(id, hotels map (_.id))
    case NodeManager.SaveCategory(topics) =>
      db ! data.db.DbActor.SaveCategory(id, topics)
  }
}