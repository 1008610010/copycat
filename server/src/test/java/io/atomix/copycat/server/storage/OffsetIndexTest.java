/*
 * Copyright 2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.atomix.copycat.server.storage;

import io.atomix.catalyst.buffer.HeapBuffer;
import org.testng.annotations.Test;

import static org.testng.Assert.*;

/**
 * Offset index test.
 *
 * @author <a href="http://github.com/kuujo">Jordan Halterman</a>
 */
@Test
public class OffsetIndexTest {

  /**
   * Tests indexing an offset and checking whether the index contains the offset.
   */
  public void testIndexContainsCommitted() {
    OffsetIndex index = new OffsetIndex(HeapBuffer.allocate(1024 * 8));
    assertFalse(index.contains(10, true));
    index.index(10, 1234);
    assertTrue(index.contains(10, true));
    assertFalse(index.contains(9, true));
    assertFalse(index.contains(11, true));
  }

  /**
   * Tests indexing an offset and checking whether the index contains the offset.
   */
  public void testIndexContainsUncommitted() {
    OffsetIndex index = new OffsetIndex(HeapBuffer.allocate(1024 * 8));
    assertFalse(index.contains(1, false));
    index.index(0, 1234);
    index.index(1, 2345);
    index.index(2, 3456);
    assertTrue(index.contains(1, false));
    assertFalse(index.contains(4, false));
  }

  /**
   * Tests reading the position and length of an offset.
   */
  public void testIndexPositionAndLength() {
    OffsetIndex index = new OffsetIndex(HeapBuffer.allocate(1024 * 8));
    index.index(1, 0);
    assertEquals(index.position(1, true), 0);
    assertEquals(index.position(10, true), -1);
    index.index(10, 1234);
    assertEquals(index.position(10, true), 1234);
    index.index(11, 1244);
    assertEquals(index.position(11, true), 1244);
    index.index(12, 3456);
    index.index(13, 4567);
    assertEquals(index.position(12, true), 3456);
    assertEquals(index.position(13, true), 4567);
  }

  /**
   * Tests truncating entries.
   */
  public void testTruncateMiddle() {
    OffsetIndex index = new OffsetIndex(HeapBuffer.allocate(1024 * 8));
    index.index(0, 0);
    index.index(1, 10);
    index.index(2, 20);
    index.index(3, 30);
    index.index(4, 40);
    assertEquals(index.truncate(2), 30);
  }

  /**
   * Tests truncating entries.
   */
  public void testTruncateLast() {
    OffsetIndex index = new OffsetIndex(HeapBuffer.allocate(1024 * 8));
    index.index(0, 0);
    index.index(1, 10);
    index.index(2, 20);
    index.index(3, 30);
    index.index(4, 40);
    assertEquals(index.truncate(3), 40);
  }

  /**
   * Tests truncating missing entries.
   */
  public void testTruncateMissing() {
    OffsetIndex index = new OffsetIndex(HeapBuffer.allocate(1024 * 8));
    index.index(0, 0);
    index.index(1, 10);
    index.index(3, 30);
    index.index(4, 40);
    assertEquals(index.truncate(2), 30);
  }

  /**
   * Tests truncating skipped entries.
   */
  public void testTruncateSkipped() {
    OffsetIndex index = new OffsetIndex(HeapBuffer.allocate(1024 * 8));
    index.index(0, 0);
    index.index(1, 10);
    index.index(3, 30);
    index.index(4, 40);
    assertEquals(index.truncate(1), 30);
  }

  /**
   * Tests truncating a deleted item from the index.
   */
  public void testTruncateDeleted() {
    OffsetIndex index = new OffsetIndex(HeapBuffer.allocate(1024 * 8));
    index.index(0, 0);
    index.index(1, 10);
    index.index(3, 30);
    index.index(4, 40);
    index.delete(1);
    assertEquals(index.truncate(1), 30);
  }

}
