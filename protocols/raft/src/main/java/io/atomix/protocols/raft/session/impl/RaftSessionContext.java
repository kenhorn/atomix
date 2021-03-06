/*
 * Copyright 2015-present Open Networking Laboratory
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.atomix.protocols.raft.session.impl;

import com.google.common.collect.Lists;
import io.atomix.protocols.raft.ReadConsistency;
import io.atomix.protocols.raft.cluster.MemberId;
import io.atomix.protocols.raft.event.RaftEvent;
import io.atomix.protocols.raft.impl.OperationResult;
import io.atomix.protocols.raft.impl.RaftServerContext;
import io.atomix.protocols.raft.operation.OperationType;
import io.atomix.protocols.raft.protocol.PublishRequest;
import io.atomix.protocols.raft.protocol.RaftServerProtocol;
import io.atomix.protocols.raft.roles.PendingCommand;
import io.atomix.protocols.raft.service.ServiceType;
import io.atomix.protocols.raft.service.impl.DefaultServiceContext;
import io.atomix.protocols.raft.session.RaftSession;
import io.atomix.protocols.raft.session.RaftSessionEvent;
import io.atomix.protocols.raft.session.RaftSessionEventListener;
import io.atomix.protocols.raft.session.SessionId;
import io.atomix.utils.TimestampPrinter;
import io.atomix.utils.logging.ContextualLoggerFactory;
import io.atomix.utils.logging.LoggerContext;
import org.slf4j.Logger;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import static com.google.common.base.MoreObjects.toStringHelper;
import static com.google.common.base.Preconditions.checkState;

/**
 * Raft session.
 */
public class RaftSessionContext implements RaftSession {
  private final Logger log;
  private final SessionId sessionId;
  private final MemberId member;
  private final String name;
  private final ServiceType serviceType;
  private final ReadConsistency readConsistency;
  private final long timeout;
  private final RaftServerProtocol protocol;
  private final DefaultServiceContext context;
  private final RaftServerContext server;
  private volatile State state = State.OPEN;
  private volatile long timestamp;
  private long requestSequence;
  private volatile long commandSequence;
  private volatile long lastApplied;
  private volatile long commandLowWaterMark;
  private volatile long eventIndex;
  private volatile long completeIndex;
  private final Map<Long, List<Runnable>> sequenceQueries = new HashMap<>();
  private final Map<Long, List<Runnable>> indexQueries = new HashMap<>();
  private final Map<Long, PendingCommand> pendingCommands = new HashMap<>();
  private final Map<Long, OperationResult> results = new HashMap<>();
  private final Queue<EventHolder> events = new LinkedList<>();
  private volatile EventHolder currentEventList;
  private final Set<RaftSessionEventListener> eventListeners = new CopyOnWriteArraySet<>();

  public RaftSessionContext(
      SessionId sessionId,
      MemberId member,
      String name,
      ServiceType serviceType,
      ReadConsistency readConsistency,
      long timeout,
      DefaultServiceContext context,
      RaftServerContext server) {
    this.sessionId = sessionId;
    this.member = member;
    this.name = name;
    this.serviceType = serviceType;
    this.readConsistency = readConsistency;
    this.timeout = timeout;
    this.eventIndex = sessionId.id();
    this.completeIndex = sessionId.id();
    this.lastApplied = sessionId.id();
    this.protocol = server.getProtocol();
    this.context = context;
    this.server = server;
    this.log = ContextualLoggerFactory.getLogger(getClass(), LoggerContext.builder(RaftSession.class)
        .addValue(sessionId)
        .add("type", context.serviceType())
        .add("name", context.serviceName())
        .build());
    protocol.registerResetListener(sessionId, request -> resendEvents(request.index()), context.executor());
  }

  @Override
  public SessionId sessionId() {
    return sessionId;
  }

  @Override
  public String serviceName() {
    return name;
  }

  @Override
  public ServiceType serviceType() {
    return serviceType;
  }

  @Override
  public MemberId memberId() {
    return member;
  }

  @Override
  public ReadConsistency readConsistency() {
    return readConsistency;
  }

  @Override
  public long timeout() {
    return timeout;
  }

  /**
   * Returns the state machine context associated with the session.
   *
   * @return The state machine context associated with the session.
   */
  public DefaultServiceContext getStateMachineContext() {
    return context;
  }

  /**
   * Returns the session update timestamp.
   *
   * @return The session update timestamp.
   */
  public long getTimestamp() {
    return timestamp;
  }

  /**
   * Updates the session timestamp.
   *
   * @param timestamp The session timestamp.
   */
  public void setTimestamp(long timestamp) {
    this.timestamp = Math.max(this.timestamp, timestamp);
  }

  @Override
  public State getState() {
    return state;
  }

  /**
   * Updates the session state.
   *
   * @param state The session state.
   */
  private void setState(State state) {
    if (this.state != state) {
      this.state = state;
      log.debug("State changed: {}", state);
      switch (state) {
        case OPEN:
          eventListeners.forEach(l -> l.onEvent(new RaftSessionEvent(RaftSessionEvent.Type.OPEN, this, getTimestamp())));
          break;
        case EXPIRED:
          eventListeners.forEach(l -> l.onEvent(new RaftSessionEvent(RaftSessionEvent.Type.EXPIRE, this, getTimestamp())));
          break;
        case CLOSED:
          eventListeners.forEach(l -> l.onEvent(new RaftSessionEvent(RaftSessionEvent.Type.CLOSE, this, getTimestamp())));
          break;
      }
    }
  }

  @Override
  public void addListener(RaftSessionEventListener listener) {
    eventListeners.add(listener);
  }

  @Override
  public void removeListener(RaftSessionEventListener listener) {
    eventListeners.remove(listener);
  }

  /**
   * Returns the session request number.
   *
   * @return The session request number.
   */
  public long getRequestSequence() {
    return requestSequence;
  }

  /**
   * Returns the next request sequence number.
   *
   * @return the next request sequence number
   */
  public long nextRequestSequence() {
    return this.requestSequence + 1;
  }

  /**
   * Sets the current request sequence number.
   *
   * @param requestSequence the current request sequence number
   */
  public void setRequestSequence(long requestSequence) {
    this.requestSequence = Math.max(this.requestSequence, requestSequence);
  }

  /**
   * Resets the current request sequence number.
   *
   * @param requestSequence The request sequence number.
   */
  public void resetRequestSequence(long requestSequence) {
    // If the request sequence number is less than the applied sequence number, update the request
    // sequence number. This is necessary to ensure that if the local server is a follower that is
    // later elected leader, its sequences are consistent for commands.
    if (requestSequence > this.requestSequence) {
      this.requestSequence = requestSequence;
    }
  }

  /**
   * Returns the session operation sequence number.
   *
   * @return The session operation sequence number.
   */
  public long getCommandSequence() {
    return commandSequence;
  }

  /**
   * Returns the next operation sequence number.
   *
   * @return The next operation sequence number.
   */
  public long nextCommandSequence() {
    return commandSequence + 1;
  }

  /**
   * Sets the session operation sequence number.
   *
   * @param sequence The session operation sequence number.
   */
  public void setCommandSequence(long sequence) {
    // For each increment of the sequence number, trigger query callbacks that are dependent on the specific sequence.
    for (long i = commandSequence + 1; i <= sequence; i++) {
      commandSequence = i;
      List<Runnable> queries = this.sequenceQueries.remove(commandSequence);
      if (queries != null) {
        for (Runnable query : queries) {
          query.run();
        }
      }
    }
  }

  /**
   * Returns the session index.
   *
   * @return The session index.
   */
  public long getLastApplied() {
    return lastApplied;
  }

  /**
   * Sets the session index.
   *
   * @param index The session index.
   */
  public void setLastApplied(long index) {
    // Query callbacks for this session are added to the indexQueries map to be executed once the required index
    // for the query is reached. For each increment of the index, trigger query callbacks that are dependent
    // on the specific index.
    for (long i = lastApplied + 1; i <= index; i++) {
      lastApplied = i;
      List<Runnable> queries = this.indexQueries.remove(lastApplied);
      if (queries != null) {
        for (Runnable query : queries) {
          query.run();
        }
      }
    }
  }

  /**
   * Registers a causal session query.
   *
   * @param sequence The session sequence number at which to execute the query.
   * @param query    The query to execute.
   */
  public void registerSequenceQuery(long sequence, Runnable query) {
    // Add a query to be run once the session's sequence number reaches the given sequence number.
    List<Runnable> queries = this.sequenceQueries.computeIfAbsent(sequence, v -> new LinkedList<Runnable>());
    queries.add(query);
  }

  /**
   * Registers a session index query.
   *
   * @param index The state machine index at which to execute the query.
   * @param query The query to execute.
   */
  public void registerIndexQuery(long index, Runnable query) {
    // Add a query to be run once the session's index reaches the given index.
    List<Runnable> queries = this.indexQueries.computeIfAbsent(index, v -> new LinkedList<>());
    queries.add(query);
  }

  /**
   * Registers a pending command.
   *
   * @param sequence the pending command sequence number
   * @param pendingCommand the pending command to register
   */
  public void registerCommand(long sequence, PendingCommand pendingCommand) {
    pendingCommands.put(sequence, pendingCommand);
  }

  /**
   * Gets a pending command.
   *
   * @param sequence the pending command sequence number
   * @return the pending command or {@code null} if no command is pending for this sequence number
   */
  public PendingCommand getCommand(long sequence) {
    return pendingCommands.get(sequence);
  }

  /**
   * Removes and returns a pending command.
   *
   * @param sequence the pending command sequence number
   * @return the pending command or {@code null} if no command is pending for this sequence number
   */
  public PendingCommand removeCommand(long sequence) {
    return pendingCommands.remove(sequence);
  }

  /**
   * Clears and returns all pending commands.
   *
   * @return a collection of pending commands
   */
  public Collection<PendingCommand> clearCommands() {
    Collection<PendingCommand> commands = Lists.newArrayList(pendingCommands.values());
    pendingCommands.clear();
    return commands;
  }

  /**
   * Registers a session result.
   * <p>
   * Results are stored in memory on all servers in order to provide linearizable semantics. When a command
   * is applied to the state machine, the command's return value is stored with the sequence number. Once the
   * client acknowledges receipt of the command output the result will be cleared from memory.
   *
   * @param sequence The result sequence number.
   * @param result   The result.
   */
  public void registerResult(long sequence, OperationResult result) {
    results.put(sequence, result);
  }

  /**
   * Clears command results up to the given sequence number.
   * <p>
   * Command output is removed from memory up to the given sequence number. Additionally, since we know the
   * client received a response for all commands up to the given sequence number, command futures are removed
   * from memory as well.
   *
   * @param sequence The sequence to clear.
   */
  public void clearResults(long sequence) {
    if (sequence > commandLowWaterMark) {
      for (long i = commandLowWaterMark + 1; i <= sequence; i++) {
        results.remove(i);
        commandLowWaterMark = i;
      }
    }
  }

  /**
   * Returns the session response for the given sequence number.
   *
   * @param sequence The response sequence.
   * @return The response.
   */
  public OperationResult getResult(long sequence) {
    return results.get(sequence);
  }

  /**
   * Returns the session event index.
   *
   * @return The session event index.
   */
  public long getEventIndex() {
    return eventIndex;
  }

  @Override
  public void publish(RaftEvent event) {
    // Store volatile state in a local variable.
    State state = this.state;
    checkState(state != State.EXPIRED, "session is expired");
    checkState(state != State.CLOSED, "session is closed");
    checkState(context.currentOperation() == OperationType.COMMAND, "session events can only be published during command execution");

    // If the client acked an index greater than the current event sequence number since we know the
    // client must have received it from another server.
    if (completeIndex > context.currentIndex()) {
      return;
    }

    // If no event has been published for this index yet, create a new event holder.
    if (this.currentEventList == null || this.currentEventList.eventIndex != context.currentIndex()) {
      long previousIndex = eventIndex;
      eventIndex = context.currentIndex();
      this.currentEventList = new EventHolder(eventIndex, previousIndex);
    }

    // Add the event to the event holder.
    this.currentEventList.events.add(event);
  }

  /**
   * Commits events for the given index.
   */
  public void commit(long index) {
    if (currentEventList != null && currentEventList.eventIndex == index) {
      events.add(currentEventList);
      sendEvents(currentEventList);
    }
    setLastApplied(index);
  }

  /**
   * Returns the index of the highest event acked for the session.
   *
   * @return The index of the highest event acked for the session.
   */
  public long getLastCompleted() {
    // If there are any queued events, return the index prior to the first event in the queue.
    EventHolder event = events.peek();
    if (event != null && event.eventIndex > completeIndex) {
      return event.eventIndex - 1;
    }
    // If no events are queued, return the highest index applied to the session.
    return lastApplied;
  }

  /**
   * Clears events up to the given sequence.
   *
   * @param index The index to clear.
   */
  private void clearEvents(long index) {
    if (index > completeIndex) {
      EventHolder event = events.peek();
      while (event != null && event.eventIndex <= index) {
        events.remove();
        completeIndex = event.eventIndex;
        event = events.peek();
      }
      completeIndex = index;
    }
  }

  /**
   * Resends events from the given sequence.
   *
   * @param index The index from which to resend events.
   */
  public void resendEvents(long index) {
    clearEvents(index);
    for (EventHolder event : events) {
      sendEvents(event);
    }
  }

  /**
   * Sends an event to the session.
   */
  private void sendEvents(EventHolder event) {
    // Only send events to the client if this server is the leader.
    if (server.isLeader()) {
      PublishRequest request = PublishRequest.newBuilder()
          .withSession(sessionId().id())
          .withEventIndex(event.eventIndex)
          .withPreviousIndex(Math.max(event.previousIndex, completeIndex))
          .withEvents(event.events)
          .build();

      log.trace("Sending {}", request);
      protocol.publish(member, request);
    }
  }

  /**
   * Expires the session.
   */
  public void expire() {
    setState(State.EXPIRED);
    protocol.unregisterResetListener(sessionId);
  }

  /**
   * Closes the session.
   */
  public void close() {
    setState(State.CLOSED);
    protocol.unregisterResetListener(sessionId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(getClass(), sessionId);
  }

  @Override
  public boolean equals(Object object) {
    return object instanceof RaftSession && ((RaftSession) object).sessionId() == sessionId;
  }

  @Override
  public String toString() {
    return toStringHelper(this)
        .addValue(context)
        .add("session", sessionId)
        .add("timestamp", TimestampPrinter.of(timestamp))
        .toString();
  }

  /**
   * Event holder.
   */
  private static class EventHolder {
    private final long eventIndex;
    private final long previousIndex;
    private final List<RaftEvent> events = new LinkedList<>();

    private EventHolder(long eventIndex, long previousIndex) {
      this.eventIndex = eventIndex;
      this.previousIndex = previousIndex;
    }
  }

}
