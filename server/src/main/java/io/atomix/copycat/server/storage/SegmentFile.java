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

import io.atomix.catalyst.util.Assert;

import java.io.File;

/**
 * Segment file utility.
 *
 * @author <a href="http://github.com/kuujo">Jordan Halterman</a>
 */
final class SegmentFile {
  private final File file;

  /**
   * Returns a boolean value indicating whether the given file appears to be a parsable segment file.
   * 
   * @throws NullPointerException if {@code file} is null
   */
  static boolean isSegmentFile(String name, File file) {
    Assert.notNull(name, "name");
    Assert.notNull(file, "file");
    String fileName = file.getName();
    if (fileName.lastIndexOf('.') == -1 || fileName.lastIndexOf('-') == -1 || fileName.lastIndexOf('.') < fileName.lastIndexOf('-') || !fileName.endsWith(".log"))
      return false;

    for (int i = fileName.lastIndexOf('-') + 1; i < fileName.lastIndexOf('.'); i++) {
      if (!Character.isDigit(fileName.charAt(i))) {
        return false;
      }
    }

    if (fileName.lastIndexOf('-', fileName.lastIndexOf('-') - 1) == -1)
      return false;

    for (int i = fileName.lastIndexOf('-', fileName.lastIndexOf('-') - 1) + 1; i < fileName.lastIndexOf('-'); i++) {
      if (!Character.isDigit(fileName.charAt(i))) {
        return false;
      }
    }

    return fileName.substring(0, fileName.lastIndexOf('-', fileName.lastIndexOf('-') - 1)).equals(name);
  }

  /**
   * Creates a segment file for the given directory, log name, segment ID, and segment version.
   */
  static File createSegmentFile(String name, File directory, long id, long version) {
    return new File(directory, String.format("%s-%d-%d.log", Assert.notNull(name, "name"), id, version));
  }

  /**
   * @throws IllegalArgumentException if {@code file} is not a valid segment file
   */
  SegmentFile(File file) {
    this.file = file;
  }

  /**
   * Returns the segment file.
   *
   * @return The segment file.
   */
  public File file() {
    return file;
  }

  /**
   * Returns the segment index file.
   *
   * @return The segment index file.
   */
  public File index() {
    return new File(file.getParentFile(), file.getName().substring(0, file.getName().lastIndexOf('.') + 1) + "index");
  }

  /**
   * Returns the segment identifier.
   */
  public long id() {
    return Long.valueOf(file.getName().substring(file.getName().lastIndexOf('-', file.getName().lastIndexOf('-') - 1) + 1, file.getName().lastIndexOf('-')));
  }

  /**
   * Returns the segment version.
   */
  public long version() {
    return Long.valueOf(file.getName().substring(file.getName().lastIndexOf('-') + 1, file.getName().lastIndexOf('.')));
  }

}
