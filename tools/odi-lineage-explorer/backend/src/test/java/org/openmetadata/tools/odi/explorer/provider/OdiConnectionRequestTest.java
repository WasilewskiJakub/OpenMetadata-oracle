/*
 *  Copyright 2026 Collate
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.openmetadata.tools.odi.explorer.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class OdiConnectionRequestTest {
  private static final String JDBC_URL = "jdbc:oracle:thin:@localhost:1521/ODIPDB";
  private static final char[] REPOSITORY_SECRET = "repository-secret".toCharArray();
  private static final char[] ODI_SECRET = "odi-secret".toCharArray();

  @Test
  void takesOwnedCopiesAndClearsConstructorBuffers() {
    final char[] repositoryPassword = Arrays.copyOf(REPOSITORY_SECRET, REPOSITORY_SECRET.length);
    final char[] odiPassword = Arrays.copyOf(ODI_SECRET, ODI_SECRET.length);
    final OdiConnectionRequest request = request(repositoryPassword, odiPassword);

    assertTrue(allCleared(repositoryPassword));
    assertTrue(allCleared(odiPassword));
    assertTrue(
        Boolean.TRUE.equals(
            request.withRepositoryPassword(
                password -> Arrays.equals(REPOSITORY_SECRET, password))));
    assertTrue(
        Boolean.TRUE.equals(
            request.withOdiPassword(password -> Arrays.equals(ODI_SECRET, password))));
  }

  @Test
  void clearsEveryScopedPasswordCopyAfterCallbackReturns() {
    final OdiConnectionRequest request = request(copy(REPOSITORY_SECRET), copy(ODI_SECRET));
    final AtomicReference<char[]> repositoryCopy = new AtomicReference<>();
    final AtomicReference<char[]> odiCopy = new AtomicReference<>();

    request.withRepositoryPassword(
        password -> {
          repositoryCopy.set(password);
          return null;
        });
    request.withOdiPassword(
        password -> {
          odiCopy.set(password);
          return null;
        });

    assertTrue(allCleared(repositoryCopy.get()));
    assertTrue(allCleared(odiCopy.get()));
  }

  @Test
  void clearsScopedPasswordCopyWhenCallbackThrows() {
    final OdiConnectionRequest request = request(copy(REPOSITORY_SECRET), copy(ODI_SECRET));
    final AtomicReference<char[]> scopedCopy = new AtomicReference<>();

    assertThrows(
        IllegalStateException.class,
        () ->
            request.withRepositoryPassword(
                password -> {
                  scopedCopy.set(password);
                  throw new IllegalStateException("failed");
                }));

    assertTrue(allCleared(scopedCopy.get()));
  }

  @Test
  void closeClearsOwnedPasswordBuffers() {
    final OdiConnectionRequest request = request(copy(REPOSITORY_SECRET), copy(ODI_SECRET));

    request.close();

    assertTrue(Boolean.TRUE.equals(request.withRepositoryPassword(this::allCleared)));
    assertTrue(Boolean.TRUE.equals(request.withOdiPassword(this::allCleared)));
  }

  @Test
  void deserializesTheSixFieldConnectionApi() throws Exception {
    final String json =
        """
        {
          "jdbcUrl": "jdbc:oracle:thin:@localhost:1521/ODIPDB",
          "repositoryUsername": "ODI_REPO",
          "repositoryPassword": "repository-secret",
          "workRepositoryName": "WORKREP",
          "odiUsername": "SUPERVISOR",
          "odiPassword": "odi-secret"
        }
        """;

    try (OdiConnectionRequest request =
        new ObjectMapper().readValue(json, OdiConnectionRequest.class)) {
      assertEquals(JDBC_URL, request.jdbcUrl());
      assertEquals("ODI_REPO", request.repositoryUsername());
      assertEquals("WORKREP", request.workRepositoryName());
      assertEquals("SUPERVISOR", request.odiUsername());
      assertTrue(
          Boolean.TRUE.equals(
              request.withRepositoryPassword(
                  password -> Arrays.equals(REPOSITORY_SECRET, password))));
      assertTrue(
          Boolean.TRUE.equals(
              request.withOdiPassword(password -> Arrays.equals(ODI_SECRET, password))));
    }
  }

  @Test
  void jacksonNeverSerializesPasswordProperties() throws Exception {
    final OdiConnectionRequest request = request(copy(REPOSITORY_SECRET), copy(ODI_SECRET));

    final String json = new ObjectMapper().writeValueAsString(request);

    assertFalse(json.contains("repositoryPassword"));
    assertFalse(json.contains("odiPassword"));
    assertFalse(json.contains("repository-secret"));
    assertFalse(json.contains("odi-secret"));
  }

  @Test
  void neverIncludesPasswordsInItsStringRepresentation() {
    final OdiConnectionRequest request = request(copy(REPOSITORY_SECRET), copy(ODI_SECRET));

    final String value = request.toString();

    assertFalse(value.contains("repository-secret"));
    assertFalse(value.contains("odi-secret"));
  }

  private OdiConnectionRequest request(char[] repositoryPassword, char[] odiPassword) {
    return new OdiConnectionRequest(
        JDBC_URL, "ODI_REPO", repositoryPassword, "WORKREP", "SUPERVISOR", odiPassword);
  }

  private char[] copy(char[] value) {
    return Arrays.copyOf(value, value.length);
  }

  private boolean allCleared(char[] value) {
    return value.length > 0 && Arrays.equals(value, new char[value.length]);
  }
}
