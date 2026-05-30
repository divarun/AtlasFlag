package com.atlasflag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class FlagChangePublisher {

    private static final Logger logger = LoggerFactory.getLogger(FlagChangePublisher.class);
    private static final long SSE_TIMEOUT_MS = 300_000L; // 5 minutes

    private final Map<String, List<SseEmitter>> emittersByEnv = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    public FlagChangePublisher(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public SseEmitter subscribe(String environment) {
        String key = environment.toUpperCase();
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);

        emittersByEnv.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>()).add(emitter);

        emitter.onCompletion(() -> removeEmitter(key, emitter));
        emitter.onTimeout(() -> { emitter.complete(); removeEmitter(key, emitter); });
        emitter.onError(e -> removeEmitter(key, emitter));

        try {
            emitter.send(SseEmitter.event().name("connected").data("{\"status\":\"connected\"}"));
        } catch (IOException e) {
            emitter.completeWithError(e);
        }

        return emitter;
    }

    public void publish(String environment, String eventName, Map<String, Object> data) {
        List<SseEmitter> emitters = emittersByEnv.getOrDefault(environment.toUpperCase(), List.of());
        if (emitters.isEmpty()) return;

        String json;
        try {
            json = objectMapper.writeValueAsString(data);
        } catch (Exception e) {
            logger.warn("Failed to serialize SSE event data", e);
            return;
        }

        List<SseEmitter> dead = new ArrayList<>();
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(json));
            } catch (IOException e) {
                dead.add(emitter);
            }
        }
        dead.forEach(e -> removeEmitter(environment.toUpperCase(), e));
    }

    private void removeEmitter(String environment, SseEmitter emitter) {
        List<SseEmitter> list = emittersByEnv.get(environment);
        if (list != null) list.remove(emitter);
    }
}
