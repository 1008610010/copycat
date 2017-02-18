/*
 * Copyright 2017 the original author or authors.
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
 * limitations under the License
 */
package io.atomix.copycat.protocol.serializers;

import io.atomix.copycat.protocol.Address;
import io.atomix.copycat.protocol.response.AbstractResponse;
import io.atomix.copycat.protocol.response.ProtocolResponse;
import io.atomix.copycat.protocol.response.RegisterResponse;
import io.atomix.copycat.util.buffer.BufferInput;
import io.atomix.copycat.util.buffer.BufferOutput;

import java.util.ArrayList;
import java.util.List;

public class RegisterResponseSerializer extends ProtocolResponseSerializer<RegisterResponse> {
  @Override
  public void writeObject(BufferOutput output, RegisterResponse response) {
    output.writeByte(response.status().id());
    if (response.status() == ProtocolResponse.Status.OK) {
      output.writeLong(response.session());
      output.writeLong(response.timeout());
      output.writeString(response.leader().host()).writeInt(response.leader().port());
      output.writeInt(response.members().size());
      for (Address address : response.members()) {
        output.writeString(address.host()).writeInt(address.port());
      }
    } else {
      output.writeByte(response.error().type().id());
      output.writeString(response.error().message());
    }
  }

  @Override
  @SuppressWarnings("unchecked")
  public RegisterResponse readObject(BufferInput input, Class<RegisterResponse> type) {
    final ProtocolResponse.Status status = ProtocolResponse.Status.forId(input.readByte());
    if (status == ProtocolResponse.Status.OK) {
      final long session = input.readLong();
      final long timeout = input.readLong();
      final Address leader = new Address(input.readString(), input.readInt());
      final int size = input.readInt();
      final List<Address> members = new ArrayList<>(size);
      for (int i = 0; i < size; i++) {
        members.add(new Address(input.readString(), input.readInt()));
      }
      return new RegisterResponse(status, null, session, leader, members, timeout);
    } else {
      ProtocolResponse.Error error = new AbstractResponse.Error(ProtocolResponse.Error.Type.forId(input.readByte()), input.readString());
      return new RegisterResponse(status, error, 0, null, null, 0);
    }
  }
}