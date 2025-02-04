/*
 * Copyright 2023 asyncer.io projects
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.asyncer.r2dbc.mysql;

import io.asyncer.r2dbc.mysql.cache.Caches;
import io.asyncer.r2dbc.mysql.client.Client;
import io.asyncer.r2dbc.mysql.codec.Codecs;
import io.asyncer.r2dbc.mysql.message.client.ClientMessage;
import io.asyncer.r2dbc.mysql.message.client.TextQueryMessage;
import io.r2dbc.spi.IsolationLevel;
import org.assertj.core.api.ThrowableTypeAssert;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MySqlConnection}.
 */
class MySqlConnectionTest {

    private final Client client = mock(Client.class);

    private final Codecs codecs = mock(Codecs.class);

    private final IsolationLevel level = IsolationLevel.REPEATABLE_READ;

    private final String product = "MockConnection";

    private final MySqlConnection noPrepare = new MySqlConnection(client, ConnectionContextTest.mock(),
        codecs, level, 50, Caches.createQueryCache(0),
        Caches.createPrepareCache(0), product, null);

    @Test
    void createStatement() {
        String condition = "SELECT * FROM test";
        MySqlConnection allPrepare = new MySqlConnection(client, ConnectionContextTest.mock(),
            codecs, level, 50, Caches.createQueryCache(0),
            Caches.createPrepareCache(0), product, sql -> true);
        MySqlConnection halfPrepare = new MySqlConnection(client, ConnectionContextTest.mock(),
            codecs, level, 50, Caches.createQueryCache(0),
            Caches.createPrepareCache(0), product, sql -> false);
        MySqlConnection conditionPrepare = new MySqlConnection(client, ConnectionContextTest.mock(),
            codecs, level, 50, Caches.createQueryCache(0),
            Caches.createPrepareCache(0), product, sql -> sql.equals(condition));

        assertThat(noPrepare.createStatement("SELECT * FROM test WHERE id=1"))
            .isExactlyInstanceOf(TextSimpleStatement.class);
        assertThat(noPrepare.createStatement(condition))
            .isExactlyInstanceOf(TextSimpleStatement.class);
        assertThat(noPrepare.createStatement("SELECT * FROM test WHERE id=?"))
            .isExactlyInstanceOf(TextParametrizedStatement.class);

        assertThat(allPrepare.createStatement("SELECT * FROM test WHERE id=1"))
            .isExactlyInstanceOf(PrepareSimpleStatement.class);
        assertThat(allPrepare.createStatement(condition))
            .isExactlyInstanceOf(PrepareSimpleStatement.class);
        assertThat(allPrepare.createStatement("SELECT * FROM test WHERE id=?"))
            .isExactlyInstanceOf(PrepareParametrizedStatement.class);

        assertThat(halfPrepare.createStatement("SELECT * FROM test WHERE id=1"))
            .isExactlyInstanceOf(TextSimpleStatement.class);
        assertThat(halfPrepare.createStatement(condition))
            .isExactlyInstanceOf(TextSimpleStatement.class);
        assertThat(halfPrepare.createStatement("SELECT * FROM test WHERE id=?"))
            .isExactlyInstanceOf(PrepareParametrizedStatement.class);

        assertThat(conditionPrepare.createStatement("SELECT * FROM test WHERE id=1"))
            .isExactlyInstanceOf(TextSimpleStatement.class);
        assertThat(conditionPrepare.createStatement(condition))
            .isExactlyInstanceOf(PrepareSimpleStatement.class);
        assertThat(conditionPrepare.createStatement("SELECT * FROM test WHERE id=?"))
            .isExactlyInstanceOf(PrepareParametrizedStatement.class);
    }

    @SuppressWarnings("ConstantConditions")
    @Test
    void badCreateStatement() {
        assertThatIllegalArgumentException().isThrownBy(() -> noPrepare.createStatement(null));
    }

    @SuppressWarnings("ConstantConditions")
    @Test
    void badCreateSavepoint() {
        ThrowableTypeAssert<?> asserted = assertThatIllegalArgumentException();

        asserted.isThrownBy(() -> noPrepare.createSavepoint(""));
        asserted.isThrownBy(() -> noPrepare.createSavepoint(null));
    }

    @SuppressWarnings("ConstantConditions")
    @Test
    void badReleaseSavepoint() {
        ThrowableTypeAssert<?> asserted = assertThatIllegalArgumentException();

        asserted.isThrownBy(() -> noPrepare.releaseSavepoint(""));
        asserted.isThrownBy(() -> noPrepare.releaseSavepoint(null));
    }

    @SuppressWarnings("ConstantConditions")
    @Test
    void badRollbackTransactionToSavepoint() {
        ThrowableTypeAssert<?> asserted = assertThatIllegalArgumentException();

        asserted.isThrownBy(() -> noPrepare.rollbackTransactionToSavepoint(""));
        asserted.isThrownBy(() -> noPrepare.rollbackTransactionToSavepoint(null));
    }

    @SuppressWarnings("ConstantConditions")
    @Test
    void badSetTransactionIsolationLevel() {
        assertThatIllegalArgumentException().isThrownBy(() -> noPrepare.setTransactionIsolationLevel(null));
    }

    @Test
    void shouldSetTransactionIsolationLevelSuccessfully() {
        ClientMessage message = new TextQueryMessage("SET SESSION TRANSACTION ISOLATION LEVEL SERIALIZABLE");
        when(client.exchange(eq(message), any())).thenReturn(Flux.empty());

        noPrepare.setTransactionIsolationLevel(IsolationLevel.SERIALIZABLE)
            .as(StepVerifier::create)
            .verifyComplete();

        assertThat(noPrepare.getSessionTransactionIsolationLevel()).isEqualTo(IsolationLevel.SERIALIZABLE);
    }

    @SuppressWarnings("ConstantConditions")
    @Test
    void badValidate() {
        assertThatIllegalArgumentException().isThrownBy(() -> noPrepare.validate(null));
    }
}
