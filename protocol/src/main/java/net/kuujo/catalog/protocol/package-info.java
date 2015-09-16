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

/**
 * The protocol layer provides a set of interfaces for communicating between Raft clients and servers in a Raft cluster.
 * <p>
 * At the core of the protocol layer are the {@link net.kuujo.catalog.protocol.request.Request} and
 * {@link net.kuujo.catalog.protocol.response.Response} interfaces. The protocol provides for all of the internal and
 * client-facing request types necessary to participate in consensus via the Raft consensus protocol.
 * <p>
 * All {@link net.kuujo.catalog.protocol.request.Request} and {@link net.kuujo.catalog.protocol.response.Response} objects are
 * {@link net.kuujo.catalyst.serializer.CatalystSerializable} and {@link net.kuujo.catalyst.util.ReferenceCounted}. This reduces
 * garbage by allowing {@link net.kuujo.catalyst.serializer.Serializer} to pool requests and responses during deserialization.
 * <p>
 * All {@link net.kuujo.catalog.protocol.request.Request}s and {@link net.kuujo.catalog.protocol.response.Response}s provide
 * a custom {@link net.kuujo.catalyst.util.Builder} implementation specific to each request's or response's attributes. Builders
 * can be created by calling the static {@code builder()} method on a given {@code Request} or {@code Response} implementation.
 * Builders are thread local and are not thread safe. All builders assume that they will be completed via
 * {@link net.kuujo.catalyst.util.Builder#build()} in the same thread in which it was created.
 *
 * @author <a href="http://github.com/kuujo">Jordan Halterman</a>
 */
package net.kuujo.catalog.protocol;
