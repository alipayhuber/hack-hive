/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hive.ql.udf.generic;

import org.apache.hadoop.hive.common.type.HiveDecimal;
import org.apache.hadoop.hive.ql.exec.Description;
import org.apache.hadoop.hive.serde2.io.HiveDecimalWritable;
import org.apache.hadoop.hive.ql.metadata.HiveException;
import org.apache.hadoop.hive.serde2.io.ByteWritable;
import org.apache.hadoop.hive.serde2.io.DoubleWritable;
import org.apache.hadoop.hive.serde2.io.ShortWritable;
import org.apache.hadoop.io.FloatWritable;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.LongWritable;

@Description(name = "-", value = "_FUNC_ a - Returns -a")
public class GenericUDFOPNegative extends GenericUDFBaseUnary {

  public GenericUDFOPNegative() {
    super();
    this.opDisplayName = "-";
  }

  @Override
  public Object evaluate(DeferredObject[] arguments) throws HiveException {
    if (arguments[0] == null) {
      return null;
    }

    Object input = arguments[0].get();
    if (input == null) {
      return null;
    }

    input = converter.convert(input);
    if (input == null) {
      return null;
    }

    switch (resultOI.getPrimitiveCategory()) {
    case BYTE:
      byteWritable.set((byte) -(((ByteWritable)input).get()));
      return byteWritable;
    case SHORT:
      shortWritable.set((short) -(((ShortWritable)input).get()));
      return shortWritable;
    case INT:
      intWritable.set(-(((IntWritable)input).get()));
      return intWritable;
    case LONG:
      longWritable.set(-(((LongWritable)input).get()));
      return longWritable;
    case FLOAT:
      floatWritable.set(-(((FloatWritable)input).get()));
      return floatWritable;
    case DOUBLE:
      doubleWritable.set(-(((DoubleWritable)input).get()));
      return doubleWritable;
    case DECIMAL:
      HiveDecimal dec = ((HiveDecimalWritable)input).getHiveDecimal();
      decimalWritable.set(dec.negate());
      return decimalWritable;
    default:
      // Should never happen.
      throw new RuntimeException("Unexpected type in evaluating " + opName + ": " +
          resultOI.getPrimitiveCategory());
    }
  }

}
