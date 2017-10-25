/*
 * Copyright 2017 Huawei Technologies Co., Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.servicecomb.saga.spring;

import static com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY;
import static com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE;
import static javax.ws.rs.core.Response.Status.BAD_REQUEST;
import static javax.ws.rs.core.Response.Status.INTERNAL_SERVER_ERROR;
import static org.springframework.http.MediaType.TEXT_PLAIN_VALUE;
import static org.springframework.web.bind.annotation.RequestMethod.GET;
import static org.springframework.web.bind.annotation.RequestMethod.POST;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.servicecomb.provider.rest.common.RestSchema;
import io.servicecomb.saga.core.NoOpSagaRequest;
import io.servicecomb.saga.core.SagaDefinition;
import io.servicecomb.saga.core.SagaEndedEvent;
import io.servicecomb.saga.core.SagaException;
import io.servicecomb.saga.core.SagaRequest;
import io.servicecomb.saga.core.SagaStartedEvent;
import io.servicecomb.saga.core.TransactionAbortedEvent;
import io.servicecomb.saga.core.TransactionEndedEvent;
import io.servicecomb.saga.core.TransactionStartedEvent;
import io.servicecomb.saga.core.application.GraphBuilder;
import io.servicecomb.saga.core.application.SagaExecutionComponent;
import io.servicecomb.saga.core.application.interpreter.FromJsonFormat;
import io.servicecomb.saga.core.dag.GraphCycleDetectorImpl;
import io.servicecomb.saga.core.dag.Node;
import io.servicecomb.saga.core.dag.SingleLeafDirectedAcyclicGraph;
import io.servicecomb.swagger.invocation.exception.InvocationException;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import kamon.annotation.EnableKamon;
import kamon.annotation.Trace;

@EnableKamon
@Controller
@RequestMapping("/")
@RestSchema(schemaId = "saga-endpoint")
public class SagaController {

  private final SagaExecutionComponent sagaExecutionComponent;
  private final SagaEventRepo repo;
  private final ObjectMapper mapper = new ObjectMapper();
  private final GraphBuilder graphBuilder = new GraphBuilder(new GraphCycleDetectorImpl<>());

  private FromJsonFormat<SagaDefinition> fromJsonFormat = null;

  @Autowired
  public SagaController(SagaExecutionComponent sagaExecutionComponent, SagaEventRepo repo,
      FromJsonFormat<SagaDefinition> fromJsonFormat) {
    this.sagaExecutionComponent = sagaExecutionComponent;
    this.repo = repo;
    this.fromJsonFormat = fromJsonFormat;
  }

  @Trace("processRequests")
  @ApiResponses({
      @ApiResponse(code = 200, response = String.class, message = "success"),
      @ApiResponse(code = 400, response = String.class, message = "illegal request content"),
      @ApiResponse(code = 500, response = String.class, message = "transaction failed")
  })
  @RequestMapping(value = "requests", method = POST, consumes = TEXT_PLAIN_VALUE, produces = TEXT_PLAIN_VALUE)
  public ResponseEntity<String> processRequests(@RequestBody String request) {
    try {
      String runResult = sagaExecutionComponent.run(request);
      if (runResult == null) {
        return ResponseEntity.ok("success");
      } else {
        throw new InvocationException(INTERNAL_SERVER_ERROR, runResult);
      }
    } catch (SagaException se) {
      throw new InvocationException(BAD_REQUEST, se);
    }
  }

  @RequestMapping(value = "events", method = GET)
  public ResponseEntity<Map<String, List<SagaEventVo>>> allEvents() {
    Iterable<SagaEventEntity> entities = repo.findAll();

    Map<String, List<SagaEventVo>> events = new LinkedHashMap<>();
    entities.forEach(e -> {
      events.computeIfAbsent(e.sagaId(), id -> new LinkedList<>());
      events.get(e.sagaId()).add(new SagaEventVo(e.id(), e.sagaId(), e.creationTime(), e.type(), e.contentJson()));
    });

    return ResponseEntity.ok(events);
  }

  @ApiResponses({
      @ApiResponse(code = 200, response = String.class, message = "success"),
      @ApiResponse(code = 400, response = String.class, message = "illegal request content"),
  })
  @RequestMapping(value = "requests", method = GET)
  public ResponseEntity<SagaExecutionQueryResult> queryExecutions(
      @RequestParam(name = "pageIndex") String pageIndex,
      @RequestParam(name = "pageSize") String pageSize,
      @RequestParam(name = "startTime") String startTime,
      @RequestParam(name = "endTime") String endTime) {
    if (isRequestParamValid(pageIndex, pageSize, startTime, endTime)) {
      List<SagaExecution> requests = new ArrayList<>();
      Page<SagaEventEntity> startEvents = repo.findByTypeAndCreationTimeBetweenOrderByIdDesc(
          SagaStartedEvent.class.getSimpleName(),
          new Date(Long.parseLong(startTime)), new Date(Long.parseLong(endTime)),
          new PageRequest(Integer.parseInt(pageIndex), Integer.parseInt(pageSize)));
      for (SagaEventEntity event : startEvents) {
        SagaEventEntity endEvent = repo
            .findFirstByTypeAndSagaId(SagaEndedEvent.class.getSimpleName(), event.sagaId());
        SagaEventEntity abortedEvent = repo
            .findFirstByTypeAndSagaId(TransactionAbortedEvent.class.getSimpleName(), event.sagaId());

        requests.add(new SagaExecution(
            event.id(),
            event.sagaId(),
            event.creationTime(),
            endEvent == null ? 0 : endEvent.creationTime(),
            endEvent == null ? "Running" : abortedEvent == null ? "OK" : "Failed"));
      }
      return ResponseEntity.ok(
          new SagaExecutionQueryResult(Integer.parseInt(pageIndex), Integer.parseInt(pageSize),
              startEvents.getTotalPages(), requests));
    } else {
      throw new InvocationException(BAD_REQUEST, "illegal request content");
    }
  }

  private boolean isRequestParamValid(String pageIndex, String pageSize, String startTime, String endTime) {
    return Integer.parseInt(pageIndex) >= 0 && Integer.parseInt(pageSize) > 0
        && Long.parseLong(startTime) <= Long.parseLong(endTime);
  }

  @ApiResponses({
      @ApiResponse(code = 200, response = String.class, message = "success"),
  })
  @RequestMapping(value = "requests/{sagaId}", method = GET)
  public ResponseEntity<SagaExecutionDetail> queryExecutionDetail(@PathVariable String sagaId) {
    SagaEventEntity[] entities = repo.findBySagaId(sagaId).toArray(new SagaEventEntity[0]);
    Optional<SagaEventEntity> sagaStartEvent = Arrays.stream(entities)
        .filter(entity -> SagaStartedEvent.class.getSimpleName().equals(entity.type())).findFirst();
    Map<String, List<String>> router = new HashMap<>();
    Map<String, String> status = new HashMap<>();
    Map<String, String> error = new HashMap<>();
    if (sagaStartEvent.isPresent()) {
      SagaDefinition definition = fromJsonFormat.fromJson(sagaStartEvent.get().contentJson());
      SingleLeafDirectedAcyclicGraph<SagaRequest> graph = graphBuilder
          .build(definition.requests());
      loopLoadGraphNodes(router, graph.root());
      Collection<SagaEventEntity> transactionStartEvents = Arrays.stream(entities)
          .filter(entity -> TransactionStartedEvent.class.getSimpleName().equals(entity.type())).collect(
              Collectors.toList());
      JsonNode root;
      for (SagaEventEntity transactionStartEvent : transactionStartEvents) {
        try {
          root = mapper.readTree(transactionStartEvent.contentJson());
        } catch (IOException e) {
          continue;
        }
        if (isEndedTransaction(entities)) {
          status.put(root.at("/id").asText(), "OK");
        } else if (isAbortedTransaction(entities)) {
          String id = root.at("/request/id").asText();
          status.put(id, "Failed");
          error.put(id, root.at("/response/body").asText());
        }
      }
    }
    return ResponseEntity.ok(new SagaExecutionDetail(router, status, error));
  }

  private boolean isAbortedTransaction(SagaEventEntity[] entities) {
    return Arrays.stream(entities)
        .anyMatch(entity -> TransactionAbortedEvent.class.getSimpleName().equals(entity.type()));
  }

  private boolean isEndedTransaction(SagaEventEntity[] entities) {
    return Arrays.stream(entities)
        .anyMatch(entity -> TransactionEndedEvent.class.getSimpleName().equals(entity.type()));
  }

  private void loopLoadGraphNodes(Map<String, List<String>> router,
      Node<SagaRequest> node) {
    if (node != null) {
      if (isNodeValid(node)) {
        List<String> point = router.computeIfAbsent(node.value().id(), key -> new ArrayList<>());
        for (Node<SagaRequest> child : node.children()) {
          point.add(child.value().id());
          loopLoadGraphNodes(router, child);
        }
      }
    }
  }

  private boolean isNodeValid(Node<SagaRequest> node) {
    return !NoOpSagaRequest.SAGA_END_REQUEST.id().equals(node.value().id())
        && node.children() != null && node.children().size() != 0;
  }

  @JsonAutoDetect(fieldVisibility = ANY, getterVisibility = NONE, setterVisibility = NONE)
  private static class SagaEventVo extends SagaEventEntity {

    private SagaEventVo(long id, String sagaId, long creationTime, String type, String contentJson) {
      super(id, sagaId, creationTime, type, contentJson);
    }
  }

  @JsonAutoDetect(fieldVisibility = ANY, getterVisibility = NONE, setterVisibility = NONE)
  static class SagaExecutionQueryResult {
    public int pageIndex;
    public int pageSize;
    public int totalPages;

    public List<SagaExecution> requests = null;

    public SagaExecutionQueryResult() {
    }

    public SagaExecutionQueryResult(int pageIndex, int pageSize, int totalPages, List<SagaExecution> requests) {
      this();
      this.pageIndex = pageIndex;
      this.pageSize = pageSize;
      this.totalPages = totalPages;
      this.requests = requests;
    }
  }

  @JsonAutoDetect(fieldVisibility = ANY, getterVisibility = NONE, setterVisibility = NONE)
  static class SagaExecution {
    public long id;
    public String sagaId;
    public long startTime;
    public String status;
    public long completedTime;

    public SagaExecution() {
    }

    public SagaExecution(long id, String sagaId, long startTime, long completedTime, String status) {
      this();
      this.id = id;
      this.sagaId = sagaId;
      this.startTime = startTime;
      this.completedTime = completedTime;
      this.status = status;
    }
  }

  @JsonAutoDetect(fieldVisibility = ANY, getterVisibility = NONE, setterVisibility = NONE)
  static class SagaExecutionDetail {
    public Map<String, List<String>> router;
    public Map<String, String> status;
    public Map<String, String> error;

    public SagaExecutionDetail() {
    }

    public SagaExecutionDetail(Map<String, List<String>> router, Map<String, String> status,
        Map<String, String> error) {
      this();
      this.router = router;
      this.status = status;
      this.error = error;
    }
  }
}
