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

import io.atomix.catalyst.buffer.Buffer;
import io.atomix.catalyst.buffer.FileBuffer;
import io.atomix.catalyst.buffer.MappedBuffer;
import io.atomix.catalyst.buffer.util.BitArray;
import io.atomix.catalyst.util.Assert;

import java.io.IOException;

/**
 * Segment offset index.
 * <p>
 * The offset index handles indexing of entries in a given {@link Segment}. Given the offset and position of an entry
 * in a segment, the index will write the position to an underlying {@link io.atomix.catalyst.buffer.Buffer}. With this information, the index provides
 * useful metadata about the log such as the number of physical entries in the log and the first and last offsets.
 * <p>
 * Each entry in the index is stored in 8 bytes, a 1 byte status flag, a 24-bit unsigned offset, and a 32-bit unsigned
 * position. This places a limitation on the maximum indexed offset at {@code 2^31 - 1} and maximum indexed position at
 * {@code 2^32 - 1}.
 * <p>
 * When the index is first created, the {@link io.atomix.catalyst.buffer.Buffer} provided to the constructor will be scanned for existing entries.
 * <p>
 * The index assumes that entries will always be indexed in increasing order. However, this index also allows arbitrary
 * entries to be missing from the log due to log compaction. Because of the potential for missing entries, binary search
 * is used to locate positions rather than absolute positions. For efficiency, a {@link io.atomix.catalyst.buffer.MappedBuffer}
 * can be used to improve the performance of the binary search algorithm for persistent indexes.
 * <p>
 * In order to prevent searching the index for missing entries, all offsets are added to a memory efficient {@link io.atomix.catalyst.buffer.util.BitArray}
 * as they're written to the index. The bit array is sized according to the underlying index buffer. Prior to searching
 * for an offset in the index, the {@link io.atomix.catalyst.buffer.util.BitArray} is checked for existence of the offset in the index. Only if the offset
 * exists in the index is a binary search required.
 *
 * @author <a href="http://github.com/kuujo">Jordan Halterman</a>
 */
class OffsetIndex implements AutoCloseable {

  /**
   * Returns the count of the index for the given number of entries.
   */
  public static long size(int maxEntries) {
    return (long) maxEntries * 8 + 16;
  }

  private static final long MAX_POSITION = (long) Math.pow(2, 32) - 1;
  private static final int ENTRY_SIZE = 8;
  private static final int OFFSET_SIZE = 4;

  private final Buffer buffer;
  private final BitArray deletes;
  private int offset;
  private int size;
  private int lastOffset = -1;
  private int currentOffset = -1;

  /**
   * @throws NullPointerException if {@code buffer} is null
   */
  public OffsetIndex(Buffer buffer) {
    this.buffer = Assert.notNull(buffer, "buffer");
    this.deletes = BitArray.allocate(1024);
  }

  /**
   * Returns the index offset.
   *
   * @return The index offset.
   */
  public int offset() {
    return offset;
  }

  /**
   * Resets the index offset.
   *
   * @param offset The index offset.
   */
  public void resetOffset(int offset) {
    this.offset = offset;
  }

  /**
   * Returns the last offset in the index.
   */
  public int lastOffset() {
    return lastOffset;
  }

  /**
   * Indexes the given offset with the given position.
   *
   * @param offset The offset to index.
   * @param position The position of the offset to index.
   * @throws IllegalArgumentException if the {@code offset} is less than or equal to the last offset in the index, 
   * or {@code position} is greater than MAX_POSITION
   */
  public void index(int offset, long position) {
    Assert.argNot(offset, lastOffset > -1 && offset <= lastOffset,
      "offset cannot be less than or equal to the last offset in the index");
    Assert.argNot(position > MAX_POSITION, "position cannot be greater than " + MAX_POSITION);

    buffer.writeInt(offset).writeUnsignedInt(position);

    size++;
    lastOffset = offset;
  }

  /**
   * Returns a boolean value indicating whether the index is empty.
   *
   * @return Indicates whether the index is empty.
   */
  public boolean isEmpty() {
    return size == 0;
  }

  /**
   * Returns the number of entries active in the index.
   *
   * @return The number of entries active in the index.
   */
  public int size() {
    return size;
  }

  /**
   * Returns a boolean value indicating whether the index contains the given offset.
   *
   * @param offset The offset to check.
   * @return Indicates whether the index contains the given offset.
   */
  public boolean contains(int offset, boolean committed) {
    return !committed ? offset <= lastOffset : position(offset, true) != -1;
  }

  /**
   * Finds the starting position of the given offset.
   *
   * @param offset The offset to look up.
   * @return The starting position of the given offset.
   */
  public long position(int offset, boolean committed) {
    // Perform a binary search to get the index of the offset in the index buffer.
    if (!committed) {
      return buffer.readUnsignedInt(offset * ENTRY_SIZE + OFFSET_SIZE);
    } else {
      int index = search(offset);
      return index == -1 ? -1 : buffer.readUnsignedInt(index + OFFSET_SIZE);
    }
  }

  /**
   * Returns the relative offset for the given offset.
   */
  private int relativeOffset(int offset) {
    if (currentOffset != -1 && currentOffset > lastOffset && buffer.readInt((currentOffset + 1) * ENTRY_SIZE) == offset) {
      return ++currentOffset;
    }

    if (size == 0) {
      return -1;
    }

    int lo = 0;
    int hi = size - 1;

    while (lo < hi) {
      int mid = lo + (hi - lo) / 2;
      int i = buffer.readInt(mid * ENTRY_SIZE);
      if (i == offset) {
        currentOffset = mid;
        return mid;
      } else if (lo == mid) {
        i = buffer.readInt(hi * ENTRY_SIZE);
        if (i == offset) {
          currentOffset = hi;
          return hi;
        }
        return -1;
      } else if (i < offset) {
        lo = mid;
      } else {
        hi = mid - 1;
      }
    }

    if (buffer.readInt(hi * ENTRY_SIZE) == offset) {
      currentOffset = hi;
      return hi;
    }
    return -1;
  }

  /**
   * Returns the offset nearest the given offset.
   */
  private int nearestOffset(int offset) {
    if (size == 0) {
      return -1;
    }

    int low  = 0;
    int high = size-1;

    while (low <= high) {
      int mid = low + ((high - low) / 2);

      int i = buffer.readInt(mid * ENTRY_SIZE);
      if (i < offset) {
        low = mid + 1;
      } else if (i > offset) {
        high = mid - 1;
      } else {
        return mid;
      }
    }

    return (low < high) ? low + 1 : high + 1;
  }

  /**
   * Performs a binary search to find the given offset in the buffer.
   */
  private int search(int offset) {
    int relativeOffset = relativeOffset(offset);
    return relativeOffset != -1 ? relativeOffset * ENTRY_SIZE : -1;
  }

  /**
   * Truncates the index up to the given offset.
   * <p>
   * This method assumes that the given offset is contained within the index. If the offset is not indexed then the
   * index will not be truncated.
   *
   * @param offset The offset after which to truncate the index.
   */
  public long truncate(int offset) {
    if (offset == lastOffset)
      return -1;

    if (offset == -1) {
      buffer.position(0).zero();
      currentOffset = lastOffset = -1;
      return 0;
    }

    int nearestOffset = nearestOffset(offset + 1);

    if (nearestOffset == -1)
      return -1;

    int nearestIndex = nearestOffset * ENTRY_SIZE;

    int lastOffset = lastOffset();
    for (int i = lastOffset; i > offset; i--) {
      if (position(i, false) != -1) {
        size--;
      }
    }

    long position = buffer.readUnsignedInt(nearestIndex + OFFSET_SIZE);

    buffer.position(nearestIndex)
      .zero(nearestIndex);
    this.lastOffset = offset;
    currentOffset = -1;

    return position;
  }

  /**
   * Deletes the given offset from the index.
   *
   * @param offset The offset to delete.
   */
  public boolean delete(int offset) {
    int relativeOffset = relativeOffset(offset);
    if (relativeOffset == -1) {
      return false;
    }

    if (deletes.size() <= relativeOffset) {
      while (deletes.size() <= relativeOffset) {
        deletes.resize(deletes.size() * 2);
      }
    }
    return deletes.set(relativeOffset);
  }

  /**
   * Returns whether the given offset has been deleted from the index.
   *
   * @param offset The offset to check.
   * @return Whether the offset has been marked for deletion.
   */
  public boolean deleted(int offset) {
    int relativeOffset = relativeOffset(offset);
    return relativeOffset == -1 || (deletes.size() > relativeOffset && deletes.get(relativeOffset));
  }

  /**
   * Returns the number of deletes in the index.
   *
   * @return The number of deletes in the index.
   */
  public int deletes() {
    return (int) deletes.count();
  }

  /**
   * Flushes the index to the underlying storage.
   */
  public void flush() {
    buffer.flush();
  }

  @Override
  public void close() {
    try {
      buffer.close();
      deletes.close();
    } catch (IOException e) {
      throw new StorageException(e);
    }
  }

  /**
   * Deletes the index.
   */
  public void delete() {
    if (buffer instanceof FileBuffer) {
      ((FileBuffer) buffer).delete();
    } else if (buffer instanceof MappedBuffer) {
      ((MappedBuffer) buffer).delete();
    }
  }

}
