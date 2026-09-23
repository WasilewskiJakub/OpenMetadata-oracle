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

package org.openmetadata.tools.odi.explorer.http;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Objects;

final class BoundedByteArrayOutputStream extends ByteArrayOutputStream {
  private static final int INITIAL_CAPACITY = 8 * 1024;
  private final int maxBytes;

  BoundedByteArrayOutputStream(int maxBytes) {
    super(initialCapacity(maxBytes));
    this.maxBytes = maxBytes;
  }

  @Override
  public synchronized void write(int value) {
    requireCapacity(1);
    ensureBoundedCapacity(count + 1);
    buf[count] = (byte) value;
    count++;
  }

  @Override
  public synchronized void write(byte[] bytes, int offset, int length) {
    Objects.checkFromIndexSize(offset, length, bytes.length);
    requireCapacity(length);
    ensureBoundedCapacity(count + length);
    System.arraycopy(bytes, offset, buf, count, length);
    count += length;
  }

  private static int initialCapacity(int maxBytes) {
    if (maxBytes <= 0) {
      throw new IllegalArgumentException("Output limit must be positive");
    }
    return Math.min(INITIAL_CAPACITY, maxBytes);
  }

  private void ensureBoundedCapacity(int requiredBytes) {
    if (requiredBytes > buf.length) {
      final int doubledCapacity = Math.min(maxBytes, buf.length << 1);
      final int newCapacity = Math.max(requiredBytes, doubledCapacity);
      buf = Arrays.copyOf(buf, newCapacity);
    }
  }

  private void requireCapacity(int additionalBytes) {
    if (additionalBytes > maxBytes - count) {
      throw new LimitExceededException();
    }
  }

  static final class LimitExceededException extends RuntimeException {}
}
