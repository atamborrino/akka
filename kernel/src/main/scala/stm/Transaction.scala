/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel.stm

import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}
import kernel.state.Transactional
import kernel.util.Logging

import scala.collection.mutable.{HashSet, HashMap}

@serializable sealed abstract class TransactionStatus
object TransactionStatus {
  case object New extends TransactionStatus
  case object Active extends TransactionStatus
  case object Aborted extends TransactionStatus
  case object Completed extends TransactionStatus
}

/**
 * Represents a snapshot of the current invocation.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object TransactionIdFactory {
  // FIXME: will not work in distributed env
  private val currentId = new AtomicLong(0L)
  def newId = currentId.getAndIncrement
}

/**
 * Represents a snapshot of the current invocation.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
@serializable class Transaction extends Logging {
  val id = TransactionIdFactory.newId

  log.debug("Creating a new transaction with id [%s]", id)

  private[this] val transactionals = new ChangeSet

  private[this] var participants: List[String] = Nil
  private[this] var precommitted: List[String] = Nil
  
  private[this] val depth = new AtomicInteger(0)
  
  @volatile private[this] var status: TransactionStatus = TransactionStatus.New

  def increment = synchronized { depth.incrementAndGet }
  def decrement = synchronized { depth.decrementAndGet }
  def topLevel_? = synchronized { depth.get == 0 }
  
  def register(transactional: Transactional) = synchronized {
    ensureIsActiveOrNew
    transactionals + transactional
  }

  def begin(participant: String) = synchronized {
    ensureIsActiveOrNew
    if (status == TransactionStatus.New) log.debug("TX BEGIN - Server [%s] is starting NEW transaction [%s]", participant, toString)
    else log.debug("Server [%s] is participating in transaction", participant)
    participants ::= participant
    status = TransactionStatus.Active
  }

  def precommit(participant: String) = synchronized {
    if (status == TransactionStatus.Active) {
      log.debug("TX PRECOMMIT - Pre-committing transaction [%s] for server [%s]", toString, participant)
      precommitted ::= participant
    }
  }

  def commit(participant: String) = synchronized {
    if (status == TransactionStatus.Active) {
      log.debug("TX COMMIT - Committing transaction [%s] for server [%s]", toString, participant)
      val haveAllPreCommitted =
        if (participants.size == precommitted.size) {{
          for (part <- participants) yield {
            if (precommitted.exists(_ == part)) true
            else false
          }}.exists(_ == true)
        } else false
      if (haveAllPreCommitted) {
        transactionals.items.foreach(_.commit)
        status = TransactionStatus.Completed
      } else rollback(participant)
    }
    reset
  }

  def rollback(participant: String) = synchronized {
    ensureIsActiveOrAborted
    log.debug("TX ROLLBACK - Server [%s] has initiated transaction rollback for [%s]", participant, toString)
    transactionals.items.foreach(_.rollback)
    status = TransactionStatus.Aborted
    reset
  }

  def join(participant: String) = synchronized {
    ensureIsActive
    log.debug("TX JOIN - Server [%s] is joining transaction [%s]" , participant, toString)
    participants ::= participant
  }

  def isNew = status == TransactionStatus.New
  def isActive = status == TransactionStatus.Active
  def isCompleted = status == TransactionStatus.Completed
  def isAborted = status == TransactionStatus.Aborted

  private def reset = {
    transactionals.clear
    participants = Nil
    precommitted = Nil    
  }
  
  private def ensureIsActive = if (status != TransactionStatus.Active)
    throw new IllegalStateException("Expected ACTIVE transaction - current status [" + status + "]: " + toString)

  private def ensureIsActiveOrAborted = if (!(status == TransactionStatus.Active || status == TransactionStatus.Aborted))
    throw new IllegalStateException("Expected ACTIVE or ABORTED transaction - current status [" + status + "]: " + toString)

  private def ensureIsActiveOrNew = if (!(status == TransactionStatus.Active || status == TransactionStatus.New))
    throw new IllegalStateException("Expected ACTIVE or NEW transaction - current status [" + status + "]: " + toString)

  // For reinitialize transaction after sending it over the wire 
  private[kernel] def reinit = {
    import net.lag.logging.{Logger, Level}
    if (log == null) {
      log = Logger.get(this.getClass.getName)
      log.setLevel(Level.ALL)
    }
  }

  override def equals(that: Any): Boolean = synchronized {
    that != null && 
    that.isInstanceOf[Transaction] && 
    that.asInstanceOf[Transaction].id == this.id
  }
 
  override def hashCode(): Int = id.toInt
 
  override def toString(): String = synchronized { 
    "Transaction[" + id + ", " + status + "]"
  }
}
