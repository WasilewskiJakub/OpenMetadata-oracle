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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonProperty.Access;
import java.util.Arrays;
import java.util.Objects;

public final class OdiConnectionRequest implements AutoCloseable {
  private final String jdbcUrl;
  private final String repositoryUsername;
  private final char[] repositoryPassword;
  private final String workRepositoryName;
  private final String odiUsername;
  private final char[] odiPassword;

  @JsonCreator
  public OdiConnectionRequest(
      @JsonProperty("jdbcUrl") String jdbcUrl,
      @JsonProperty("repositoryUsername") String repositoryUsername,
      @JsonProperty(value = "repositoryPassword", access = Access.WRITE_ONLY)
          char[] repositoryPassword,
      @JsonProperty("workRepositoryName") String workRepositoryName,
      @JsonProperty("odiUsername") String odiUsername,
      @JsonProperty(value = "odiPassword", access = Access.WRITE_ONLY) char[] odiPassword) {
    final OwnedPasswords passwords = takeOwnedPasswords(repositoryPassword, odiPassword);
    this.jdbcUrl = jdbcUrl;
    this.repositoryUsername = repositoryUsername;
    this.repositoryPassword = passwords.repository();
    this.workRepositoryName = workRepositoryName;
    this.odiUsername = odiUsername;
    this.odiPassword = passwords.odi();
  }

  @JsonProperty("jdbcUrl")
  public String jdbcUrl() {
    return jdbcUrl;
  }

  @JsonProperty("repositoryUsername")
  public String repositoryUsername() {
    return repositoryUsername;
  }

  @JsonProperty("workRepositoryName")
  public String workRepositoryName() {
    return workRepositoryName;
  }

  @JsonProperty("odiUsername")
  public String odiUsername() {
    return odiUsername;
  }

  @JsonIgnore
  public synchronized boolean hasRepositoryPassword() {
    return hasNonWhitespaceCharacter(repositoryPassword);
  }

  @JsonIgnore
  public synchronized boolean hasOdiPassword() {
    return hasNonWhitespaceCharacter(odiPassword);
  }

  @JsonIgnore
  public synchronized <T> T withRepositoryPassword(SecretFunction<T> operation) {
    return withPassword(repositoryPassword, operation);
  }

  @JsonIgnore
  public synchronized <T> T withOdiPassword(SecretFunction<T> operation) {
    return withPassword(odiPassword, operation);
  }

  @Override
  public synchronized void close() {
    Arrays.fill(repositoryPassword, '\0');
    Arrays.fill(odiPassword, '\0');
  }

  @Override
  public String toString() {
    return "OdiConnectionRequest[jdbcUrl=%s, repositoryUsername=%s, repositoryPassword=<redacted>, "
        + "workRepositoryName=%s, odiUsername=%s, odiPassword=<redacted>]"
            .formatted(jdbcUrl, repositoryUsername, workRepositoryName, odiUsername);
  }

  private static char[] takeOwnedCopy(char[] source) {
    if (source == null) {
      return new char[0];
    }
    try {
      return Arrays.copyOf(source, source.length);
    } finally {
      Arrays.fill(source, '\0');
    }
  }

  private static OwnedPasswords takeOwnedPasswords(char[] repository, char[] odi) {
    final char[] repositoryCopy = takeOwnedCopy(repository);
    OwnedPasswords result;
    try {
      result = new OwnedPasswords(repositoryCopy, takeOwnedCopy(odi));
    } catch (RuntimeException | Error exception) {
      Arrays.fill(repositoryCopy, '\0');
      throw exception;
    }
    return result;
  }

  private static <T> T withPassword(char[] source, SecretFunction<T> operation) {
    final char[] scopedCopy = Arrays.copyOf(source, source.length);
    try {
      return Objects.requireNonNull(operation).apply(scopedCopy);
    } finally {
      Arrays.fill(scopedCopy, '\0');
    }
  }

  private static boolean hasNonWhitespaceCharacter(char[] value) {
    boolean hasValue = false;
    int index = 0;
    while (!hasValue && index < value.length) {
      hasValue = !Character.isWhitespace(value[index]);
      index++;
    }
    return hasValue;
  }

  @FunctionalInterface
  public interface SecretFunction<T> {
    T apply(char[] secret);
  }

  private record OwnedPasswords(char[] repository, char[] odi) {}
}
