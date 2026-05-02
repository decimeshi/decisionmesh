package com.decisionmesh.streaming.worker;

public interface ProjectionWorker {

    void handle(String eventType, String payloadJson);

}