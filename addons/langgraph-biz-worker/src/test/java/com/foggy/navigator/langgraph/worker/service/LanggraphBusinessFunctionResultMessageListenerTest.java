package com.foggy.navigator.langgraph.worker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.agent.framework.protocol.AgentMessage;
import com.foggy.navigator.agent.framework.protocol.MessageType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class LanggraphBusinessFunctionResultMessageListenerTest {

    private final LanggraphTaskService taskService = mock(LanggraphTaskService.class);
    private final LanggraphBusinessFunctionResultMessageListener listener =
            new LanggraphBusinessFunctionResultMessageListener(taskService, new ObjectMapper());

    @Test
    void completesVisibleWorkerTaskWhenBusinessFunctionResultSucceeds() {
        AgentMessage message = AgentMessage.of(
                "worker_session_1",
                "business-agent",
                MessageType.TEXT_COMPLETE,
                Map.of(
                        "subtype", "business_function_result_message",
                        "content", "车辆创建完成。",
                        "status", "SUCCESS",
                        "executionStatus", "COMPLETED",
                        "workerTaskId", "lgt_1",
                        "businessTaskId", "obt_1"
                ));
        message.setTaskId("lgt_1");

        listener.handleAgentMessage(message);

        verify(taskService).completeTask(
                eq("lgt_1"),
                eq("车辆创建完成。"),
                argThat(json -> json != null
                        && json.contains("\"subtype\":\"business_function_result_message\"")
                        && json.contains("\"workerTaskId\":\"lgt_1\"")
                        && json.contains("\"businessTaskId\":\"obt_1\"")
                        && json.contains("\"executionStatus\":\"COMPLETED\"")),
                isNull());
        verify(taskService, never()).completeTask(eq("obt_1"), eq("车辆创建完成。"), any(), isNull());
    }

    @Test
    void failsVisibleWorkerTaskWhenBusinessFunctionResultFails() {
        AgentMessage message = AgentMessage.of(
                "worker_session_1",
                "business-agent",
                MessageType.TEXT_COMPLETE,
                Map.of(
                        "subtype", "business_function_result_message",
                        "content", "业务函数执行失败：参数错误",
                        "status", "FAILED",
                        "executionStatus", "FAILED",
                        "workerTaskId", "lgt_1",
                        "businessTaskId", "obt_1"
                ));
        message.setTaskId("lgt_1");

        listener.handleAgentMessage(message);

        verify(taskService).failTask("lgt_1", "业务函数执行失败：参数错误");
    }

    @Test
    void ignoresBusinessFunctionResultWithoutWorkerTaskId() {
        AgentMessage message = AgentMessage.of(
                "business_session_1",
                "business-agent",
                MessageType.TEXT_COMPLETE,
                Map.of(
                        "subtype", "business_function_result_message",
                        "content", "车辆创建完成。",
                        "status", "SUCCESS",
                        "executionStatus", "COMPLETED",
                        "businessTaskId", "obt_1"
                ));
        message.setTaskId("obt_1");

        listener.handleAgentMessage(message);

        verify(taskService, never()).completeTask(
                anyString(),
                anyString(),
                any(),
                any());
        verify(taskService, never()).failTask(
                anyString(),
                anyString());
    }

    @Test
    void ignoresNonBusinessFunctionResultMessages() {
        AgentMessage message = AgentMessage.of(
                "worker_session_1",
                "langgraph-biz-worker",
                MessageType.TEXT_COMPLETE,
                Map.of("content", "普通回复", "taskId", "lgt_1"));
        message.setTaskId("lgt_1");

        listener.handleAgentMessage(message);

        verify(taskService, never()).completeTask(
                anyString(),
                anyString(),
                any(),
                any());
        verify(taskService, never()).failTask(
                anyString(),
                anyString());
    }
}
