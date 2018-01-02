/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.servicecomb.saga.omega.transaction.spring;

import static com.seanyinx.github.unit.scaffolding.AssertUtils.expectFailing;
import static com.seanyinx.github.unit.scaffolding.Randomness.uniquify;
import static io.servicecomb.saga.omega.transaction.spring.TransactionalUserService.ILLEGAL_USER;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit4.SpringRunner;

import io.servicecomb.saga.omega.context.OmegaContext;
import io.servicecomb.saga.omega.context.UniqueIdGenerator;
import io.servicecomb.saga.omega.transaction.MessageHandler;
import io.servicecomb.saga.omega.transaction.MessageSender;
import io.servicecomb.saga.omega.transaction.TxAbortedEvent;
import io.servicecomb.saga.omega.transaction.TxCompensatedEvent;
import io.servicecomb.saga.omega.transaction.TxEndedEvent;
import io.servicecomb.saga.omega.transaction.TxStartedEvent;
import io.servicecomb.saga.omega.transaction.spring.TransactionInterceptionTest.MessageConfig;

@RunWith(SpringRunner.class)
@SpringBootTest(classes = {TransactionTestMain.class, MessageConfig.class})
@AutoConfigureMockMvc
public class TransactionInterceptionTest {
  private static final String TX_STARTED_EVENT = "TxStartedEvent";
  private static final String TX_ENDED_EVENT = "TxEndedEvent";
  private static final String globalTxId = UUID.randomUUID().toString();
  private final String localTxId = UUID.randomUUID().toString();
  private final String parentTxId = UUID.randomUUID().toString();
  private final String username = uniquify("username");
  private final String email = uniquify("email");

  @Autowired
  private List<String> messages;

  @Autowired
  private TransactionalUserService userService;

  @Autowired
  private OmegaContext omegaContext;

  @Autowired
  private UserRepository userRepository;

  @Autowired
  private MessageHandler messageHandler;

  @Before
  public void setUp() throws Exception {
    omegaContext.setGlobalTxId(globalTxId);
    omegaContext.setLocalTxId(localTxId);
    omegaContext.setParentTxId(parentTxId);
  }

  @After
  public void tearDown() throws Exception {
    messages.clear();
  }

  @Test
  public void sendsUserToRemote_AroundTransaction() throws Exception {
    User user = userService.add(new User(username, email));

    String compensationMethod = TransactionalUserService.class.getDeclaredMethod("delete", User.class).toString();

    assertArrayEquals(
        new String[]{
            new TxStartedEvent(globalTxId, localTxId, parentTxId, compensationMethod, user).toString(),
            new TxEndedEvent(globalTxId, localTxId, parentTxId, compensationMethod).toString()},
        toArray(messages)
    );

    User actual = userRepository.findOne(user.id());
    assertThat(actual, is(user));
  }

  @Test
  public void sendsAbortEvent_OnSubTransactionFailure() throws Exception {
    Throwable throwable = null;
    User user = new User(ILLEGAL_USER, email);
    try {
      userService.add(user);
      expectFailing(IllegalArgumentException.class);
    } catch (IllegalArgumentException ignored) {
      throwable = ignored;
    }

    String compensationMethod = TransactionalUserService.class.getDeclaredMethod("delete", User.class).toString();

    assertArrayEquals(
        new String[]{
            new TxStartedEvent(globalTxId, localTxId, parentTxId, compensationMethod, user).toString(),
            new TxAbortedEvent(globalTxId, localTxId, parentTxId, compensationMethod, throwable).toString()},
        toArray(messages)
    );
  }

  @Test
  public void compensateOnTransactionException() throws Exception {
    User user = userService.add(new User(username, email));

    // another sub transaction to the same service within the same global transaction
    String localTxId = omegaContext.newLocalTxId();
    User anotherUser = userService.add(new User(uniquify("Jack"), uniquify("jack@gmail.com")));

    String compensationMethod = TransactionalUserService.class.getDeclaredMethod("delete", User.class).toString();

    messageHandler.onReceive(globalTxId, this.localTxId, parentTxId, compensationMethod, user);
    messageHandler.onReceive(globalTxId, localTxId, parentTxId, compensationMethod, anotherUser);

    assertThat(userRepository.findOne(user.id()), is(nullValue()));
    assertThat(userRepository.findOne(anotherUser.id()), is(nullValue()));

    assertArrayEquals(
        new String[]{
            new TxStartedEvent(globalTxId, this.localTxId, parentTxId, compensationMethod, user).toString(),
            new TxEndedEvent(globalTxId, this.localTxId, parentTxId, compensationMethod).toString(),
            new TxStartedEvent(globalTxId, localTxId, parentTxId, compensationMethod, anotherUser).toString(),
            new TxEndedEvent(globalTxId, localTxId, parentTxId, compensationMethod).toString(),
            new TxCompensatedEvent(globalTxId, this.localTxId, parentTxId, compensationMethod).toString(),
            new TxCompensatedEvent(globalTxId, localTxId, parentTxId, compensationMethod).toString()
        },
        toArray(messages)
    );
  }

  private String[] toArray(List<String> messages) {
    return messages.toArray(new String[messages.size()]);
  }

  @Configuration
  static class MessageConfig {
    private final List<String> messages = new ArrayList<>();

    @Bean
    OmegaContext omegaContext() {
      return new OmegaContext(new UniqueIdGenerator());
    }

    @Bean
    List<String> messages() {
      return messages;
    }

    @Bean
    MessageSender sender() {
      return (event) -> messages.add(event.toString());
    }
  }
}