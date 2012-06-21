/*
 * Copyright 2011 JBoss, by Red Hat, Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jboss.errai.marshalling.server.marshallers;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.Charset;

import org.jboss.errai.codegen.meta.MetaField;
import org.jboss.errai.codegen.meta.MetaMethod;
import org.jboss.errai.common.client.protocols.SerializationParts;
import org.jboss.errai.marshalling.client.api.Marshaller;
import org.jboss.errai.marshalling.client.api.MarshallingSession;
import org.jboss.errai.marshalling.client.api.exceptions.MarshallingException;
import org.jboss.errai.marshalling.client.api.json.EJObject;
import org.jboss.errai.marshalling.client.api.json.EJValue;
import org.jboss.errai.marshalling.client.util.NumbersUtils;
import org.jboss.errai.marshalling.rebind.DefinitionsFactory;
import org.jboss.errai.marshalling.rebind.api.model.ConstructorMapping;
import org.jboss.errai.marshalling.rebind.api.model.FactoryMapping;
import org.jboss.errai.marshalling.rebind.api.model.InstantiationMapping;
import org.jboss.errai.marshalling.rebind.api.model.Mapping;
import org.jboss.errai.marshalling.rebind.api.model.MappingDefinition;
import org.jboss.errai.marshalling.rebind.api.model.MemberMapping;
import org.jboss.errai.marshalling.server.EncodingSession;
import org.jboss.errai.marshalling.server.MappingContextSingleton;
import org.jboss.errai.marshalling.server.api.ServerMarshaller;
import org.mvel2.DataConversion;

/**
 * @author Mike Brock
 */
public class DefaultDefinitionMarshaller implements ServerMarshaller<Object> {
  static final Charset UTF_8 = Charset.forName("UTF-8");

  private final MappingDefinition definition;

  public DefaultDefinitionMarshaller(final MappingDefinition definition) {
    this.definition = definition;
  }

  public static void setProperty(Object i, Field f, Object v) {
    try {
      f.setAccessible(true);
      f.set(i, DataConversion.convert(v, f.getType()));
    }
    catch (Exception e) {
      throw new RuntimeException("could not set field (inst=" + i + "; field=" + f + "; val=" + v + ")", e);
    }
  }

  @Override
  public Class<Object> getTypeHandled() {
    return (Class<Object>) definition.getMappingClass().asClass();
  }

  @Override
  public Object demarshall(EJValue o, MarshallingSession ctx) {
    try {
      if (o.isObject() != null) {
        EJObject oMap = o.isObject();

        Object newInstance;
        if (oMap.containsKey(SerializationParts.OBJECT_ID)) {
          if (oMap.containsKey(SerializationParts.NUMERIC_VALUE)) {
            return NumbersUtils.getNumber(oMap.get(SerializationParts.ENCODED_TYPE).isString().stringValue(),
                    oMap.get(SerializationParts.NUMERIC_VALUE));
          }

          if (oMap.containsKey(SerializationParts.OBJECT_ID)) {
            final String hash = oMap.get(SerializationParts.OBJECT_ID).isString().stringValue();

            if (ctx.hasObjectHash(hash)) {
              newInstance = ctx.getObject(Object.class, hash);

              /**
               * If this only contains 2 fields, it is only a graph reference.
               */
              if (oMap.size() == 2) {
                return newInstance;
              }
            }
            else {
              if (oMap.containsKey(SerializationParts.INSTANTIATE_ONLY)) {
                newInstance = getTypeHandled().newInstance();
                ctx.recordObjectHash(hash, newInstance);
                return newInstance;
              }

              final InstantiationMapping cMapping = definition.getInstantiationMapping();

              Object[] parms = new Object[cMapping.getMappings().length];
              Class[] targetTypes = cMapping.getSignature();

              int i = 0;
              for (Mapping mapping : cMapping.getMappings()) {
                Marshaller<Object> marshaller = ctx.getMarshallerInstance(mapping.getType().getFullyQualifiedName());
                parms[i] = DataConversion.convert(
                        marshaller.demarshall(oMap.get(mapping.getKey()), ctx), targetTypes[i++]);
              }

              if (cMapping instanceof ConstructorMapping) {
                final Constructor constructor = ((ConstructorMapping) cMapping).getMember().asConstructor();
                constructor.setAccessible(true);
                newInstance = constructor.newInstance(parms);
              }
              else {
                newInstance = ((FactoryMapping) cMapping).getMember().asMethod().invoke(null, parms);
              }

              ctx.recordObjectHash(hash, newInstance);
            }

            for (MemberMapping mapping : definition.getWritableMemberMappings()) {
              final EJValue o1 = oMap.get(mapping.getKey());

              if (!o1.isNull()) {
                Marshaller<Object> marshaller = ctx.getMarshallerInstance(mapping.getType().getFullyQualifiedName());

                if (mapping.getBindingMember() instanceof MetaField) {
                  MetaField f = (MetaField) mapping.getBindingMember();

                  setProperty(newInstance, f.asField(),
                          marshaller.demarshall(o1, ctx));
                }
                else {
                  Method m = ((MetaMethod) mapping.getBindingMember()).asMethod();
                  m.invoke(newInstance, DataConversion.convert(
                          marshaller.demarshall(o1, ctx),
                          m.getParameterTypes()[0]));
                }
              }
            }
          }
          else {
            throw new RuntimeException("unknown type to demarshall");
          }

          return newInstance;
        }
        else if (oMap.containsKey(SerializationParts.ENUM_STRING_VALUE)) {
          return Enum.valueOf(getClassReference(oMap), oMap.get(SerializationParts.ENUM_STRING_VALUE).isString().stringValue());
        }
        else {
          throw new RuntimeException("bad payload");
        }
      }

      else {
        return o.getRawValue();
      }
    }
    catch (Exception e) {
      throw new MarshallingException("Failed to demarshall an instance of " + definition.getMappingClass(), e);
    }
  }

  @Override
  public String marshall(Object o, MarshallingSession ctx) {
    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream(1024);
    try {
      marshall(byteArrayOutputStream, o, ctx);
      return new String(byteArrayOutputStream.toByteArray());
    }
    catch (Exception e) {
      throw new RuntimeException(e);
    }
  }


  @Override
  public void marshall(final OutputStream outstream, Object o, MarshallingSession mSession) throws IOException {

    if (o == null) {
      outstream.write("null".getBytes(UTF_8));
      return;
    }

    EncodingSession ctx = (EncodingSession) mSession;
    final Class cls = o.getClass();

    if (definition.getMappingClass().isEnum()) {
      Enum enumer = (Enum) o;

      outstream.write(("{\"" + SerializationParts.ENCODED_TYPE + "\":\""
              + enumer.getDeclaringClass().getName() + "\""
              + ",\"" + SerializationParts.ENUM_STRING_VALUE + "\":\"" + enumer.name() + "\"}")
              .getBytes(UTF_8));

      return;
    }


    final boolean enc = ctx.hasObjectHash(o);
    final String hash = ctx.getObjectHash(o);

    if (enc) {
      /**
       * If this object is referencing a duplicate object in the graph, we only provide an ID reference.
       */

      outstream.write(("{\"" + SerializationParts.ENCODED_TYPE + "\":\"" + cls.getName()
              + "\",\"" + SerializationParts.OBJECT_ID + "\":\"" + hash + "\"}").getBytes(UTF_8));

      return;
    }

    int i = 0;
    boolean first = true;

    outstream.write(("{\"" + SerializationParts.ENCODED_TYPE + "\":\"" + cls.getName() + "\",\""
            + SerializationParts.OBJECT_ID + "\":\"" + hash + "\",").getBytes(UTF_8));


    for (MemberMapping mapping : definition.getReadableMemberMappings()) {
      if (!first) {
        outstream.write(',');
      }

      i++;
      Object v;

      if (mapping.getReadingMember() instanceof MetaField) {
        Field field = ((MetaField) mapping.getReadingMember()).asField();
        field.setAccessible(true);

        try {
          v = field.get(o);
        }
        catch (Exception e) {
          throw new RuntimeException("error accessing field: " + field, e);
        }
      }
      else {
        Method method = ((MetaMethod) mapping.getReadingMember()).asMethod();
        method.setAccessible(true);

        try {
          v = method.invoke(o);
        }
        catch (Exception e) {
          throw new RuntimeException("error calling getter: " + method, e);
        }
      }

      outstream.write(("\"" + mapping.getKey() + "\"").getBytes(UTF_8));
      outstream.write(':');

      if (v == null) {
        outstream.write("null".getBytes(UTF_8));
      }
      else {
        final DefinitionsFactory definitionsFactory = MappingContextSingleton.get().getDefinitionsFactory();

        if (definitionsFactory == null) {
          throw new RuntimeException("definition factory is null!");
        }

        final MappingDefinition definition1 = definitionsFactory.getDefinition(mapping.getType());

        if (definition1 == null) {
          throw new RuntimeException("no mapping definition for: " + mapping.getType().getFullyQualifiedName());
        }

        final Marshaller<Object> marshallerInstance = definition1.getMarshallerInstance();

        if (marshallerInstance == null) {
          throw new RuntimeException("no marshaller instance for: " + mapping.getType().getFullyQualifiedName());
        }

        outstream.write(
                marshallerInstance.marshall(v, ctx)
                        .getBytes(UTF_8)
        );
      }

      first = false;
    }

    if (i == 0) {
      outstream.write(("\"" + SerializationParts.INSTANTIATE_ONLY + "\":true").getBytes(UTF_8));
    }

    outstream.write('}');
  }

  public static Class getClassReference(EJObject oMap) {
    try {
      return Thread.currentThread().getContextClassLoader().loadClass(oMap.get(SerializationParts.ENCODED_TYPE).isString().stringValue());
    }
    catch (ClassNotFoundException e) {
      throw new RuntimeException("could not instantiate class", e);
    }
  }
}
