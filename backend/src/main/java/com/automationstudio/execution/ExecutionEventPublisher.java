package com.automationstudio.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
public class ExecutionEventPublisher {

  private final ExecutionEventRepository eventRepo;
  private final ObjectMapper json;
  private final Map<UUID, AtomicInteger> seqs = new ConcurrentHashMap<>();
  private final Map<UUID, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

  public ExecutionEventPublisher(ExecutionEventRepository eventRepo, ObjectMapper json) {
    this.eventRepo = eventRepo;
    this.json = json;
  }

  public void publish(UUID executionId, String type, String nodeId, Map<String, Object> payload) {
    int seq = seqs.computeIfAbsent(executionId, k -> new AtomicInteger(0)).incrementAndGet();
    ExecutionEventEntity e = new ExecutionEventEntity();
    e.executionId = executionId;
    e.seq = seq;
    e.type = type;
    e.nodeId = nodeId;
    try {
      e.payloadJson = json.writeValueAsString(payload == null ? Map.of() : payload);
    } catch (Exception ex) {
      e.payloadJson = "{}";
    }
    eventRepo.save(e);
    List<SseEmitter> list = emitters.getOrDefault(executionId, List.of());
    for (SseEmitter emitter : list) {
      try {
        emitter.send(SseEmitter.event().id(String.valueOf(seq)).name(type).data(e.payloadJson));
      } catch (IOException ex) {
        emitter.complete();
      }
    }
  }

  public SseEmitter subscribe(UUID executionId, String lastEventId) {
    SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
    emitters.computeIfAbsent(executionId, k -> new CopyOnWriteArrayList<>()).add(emitter);
    emitter.onCompletion(() -> remove(executionId, emitter));
    emitter.onTimeout(() -> remove(executionId, emitter));
    int from = 0;
    try {
      from = lastEventId == null || lastEventId.isBlank() ? 0 : Integer.parseInt(lastEventId);
    } catch (NumberFormatException ignored) {
    }
    List<ExecutionEventEntity> missed =
        eventRepo.findByExecutionIdAndSeqGreaterThanOrderBySeqAsc(executionId, from);
    try {
      for (ExecutionEventEntity e : missed) {
        emitter.send(SseEmitter.event().id(String.valueOf(e.seq)).name(e.type).data(e.payloadJson));
      }
    } catch (IOException ex) {
      emitter.complete();
    }
    return emitter;
  }

  private void remove(UUID executionId, SseEmitter emitter) {
    List<SseEmitter> list = emitters.get(executionId);
    if (list != null) list.remove(emitter);
  }

  public void close(UUID executionId) {
    List<SseEmitter> list = emitters.remove(executionId);
    if (list != null) list.forEach(SseEmitter::complete);
    seqs.remove(executionId);
  }

  @Scheduled(fixedRate = 15000)
  public void heartbeat() {
    emitters.values().forEach(list -> list.forEach(emitter -> {
      try {
        emitter.send(SseEmitter.event().comment("heartbeat"));
      } catch (IOException e) {
        emitter.complete();
      }
    }));
  }
}
