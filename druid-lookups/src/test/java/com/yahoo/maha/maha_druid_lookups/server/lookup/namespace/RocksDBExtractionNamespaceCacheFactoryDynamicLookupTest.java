// Copyright 2017, Yahoo Holdings Inc.
// Licensed under the terms of the Apache License 2.0. Please see LICENSE file in project root for terms.
package com.yahoo.maha.maha_druid_lookups.server.lookup.namespace;

import com.google.common.io.*;
import com.google.protobuf.*;
import com.yahoo.maha.maha_druid_lookups.query.lookup.dynamic.*;
import com.yahoo.maha.maha_druid_lookups.query.lookup.dynamic.schema.*;
import com.yahoo.maha.maha_druid_lookups.query.lookup.namespace.*;
import com.yahoo.maha.maha_druid_lookups.server.lookup.namespace.entity.*;
import com.yahoo.maha.maha_druid_lookups.server.lookup.namespace.schema.flatbuffer.*;
import org.apache.commons.io.*;
import org.apache.druid.java.util.emitter.service.*;
import org.joda.time.*;
import org.mockito.*;
import org.rocksdb.*;
import org.testng.*;
import org.testng.annotations.*;

import java.io.*;
import java.util.Optional;
import java.util.*;

import static java.nio.charset.StandardCharsets.*;
import static org.mockito.Matchers.*;
import static org.mockito.Mockito.*;

public class RocksDBExtractionNamespaceCacheFactoryDynamicLookupTest {

    private final String dir = "./dynamic/schema/";

    @InjectMocks
    RocksDBExtractionNamespaceCacheFactory obj =
            new RocksDBExtractionNamespaceCacheFactory();

    @InjectMocks
    RocksDBExtractionNamespaceCacheFactory noopObj =
            new RocksDBExtractionNamespaceCacheFactory();

    @Mock
    RocksDBManager rocksDBManager;

    @Mock
    ServiceEmitter serviceEmitter;

    @InjectMocks
    DynamicLookupSchema dynamicLookupSchema;

    @BeforeTest
    public void setUp() {

        MockitoAnnotations.initMocks(this);
        obj.rocksDBManager = rocksDBManager;
        obj.protobufSchemaFactory = new TestProtobufSchemaFactory();
        obj.flatBufferSchemaFactory = new TestFlatBufferSchemaFactory();
        obj.emitter = serviceEmitter;
        obj.dynamicLookupSchemaManager = new DynamicLookupSchemaManager();


        noopObj.rocksDBManager = rocksDBManager;
        noopObj.flatBufferSchemaFactory = new TestFlatBufferSchemaFactory();
        noopObj.protobufSchemaFactory = new TestProtobufSchemaFactory();
        noopObj.emitter = serviceEmitter;
        noopObj.dynamicLookupSchemaManager = new DynamicLookupSchemaManager();

        File schemaFile = new File(Thread.currentThread().getContextClassLoader().getResource(dir + "dynamic_lookup_pb_schema.json").getPath());
        Optional<DynamicLookupSchema> dynamicLookupSchemaOptional = DynamicLookupSchema.parseFrom(schemaFile);
        Assert.assertTrue(dynamicLookupSchemaOptional.isPresent(), "Failed to get the dynamic schema json");
        dynamicLookupSchema = dynamicLookupSchemaOptional.get();
    }

    @Test
    public void testUpdateCacheWithGreaterLastUpdated() throws Exception{

        Options options = null;
        RocksDB db = null;
        File tempFile = null;
        try {

            tempFile = new File(Files.createTempDir(), "rocksdblookup");
            options = new Options().setCreateIfMissing(true);
            db = RocksDB.open(options, tempFile.getAbsolutePath());

            Message msg = AdProtos.Ad.newBuilder()
                    .setId("32309719080")
                    .setTitle("some title")
                    .setStatus("ON")
                    .setLastUpdated("1470733203505")
                    .build();

            db.put("32309719080".getBytes(), msg.toByteArray());

            when(rocksDBManager.getDB(anyString())).thenReturn(db);

            RocksDBExtractionNamespace extractionNamespace = new RocksDBExtractionNamespace(
                    "ad_lookup", "blah", "blah", new Period(), "", true, false, "ad_lookup", "last_updated", null
            , "", null, false, true, 0, null);

            obj.dynamicLookupSchemaManager.updateSchema(extractionNamespace, dynamicLookupSchema);

            Message msgFromKafka = AdProtos.Ad.newBuilder()
                    .setId("32309719080")
                    .setTitle("some updated title")
                    .setStatus("OFF")
                    .setLastUpdated("1480733203505")
                    .build();

            obj.updateCache(extractionNamespace, new HashMap<>(), "32309719080", msgFromKafka.toByteArray());

            Parser<Message> parser = new TestProtobufSchemaFactory().getProtobufParser(extractionNamespace.getNamespace());
            Message updatedMessage = parser.parseFrom(db.get("32309719080".getBytes()));

            Descriptors.Descriptor descriptor = new TestProtobufSchemaFactory().getProtobufDescriptor(extractionNamespace.getNamespace());
            Descriptors.FieldDescriptor field = descriptor.findFieldByName("title");

            Assert.assertEquals(updatedMessage.getField(field).toString(), "some updated title");

            field = descriptor.findFieldByName("status");
            Assert.assertEquals(updatedMessage.getField(field).toString(), "OFF");

            field = descriptor.findFieldByName("last_updated");
            Assert.assertEquals(updatedMessage.getField(field).toString(), "1480733203505");
            Assert.assertEquals(extractionNamespace.getLastUpdatedTime().longValue(), 1480733203505L);

        } finally {
            if(db != null) {
                db.close();
            }
            if(tempFile.exists()) {
                FileUtils.forceDelete(tempFile);
            }
        }
    }

    @Test
    public void testNoopCacheActionRunner() throws Exception {
        Options options = null;
        RocksDB db = null;
        File tempFile = null;
        try {

            tempFile = new File(Files.createTempDir(), "rocksdblookup");
            options = new Options().setCreateIfMissing(true);
            db = RocksDB.open(options, tempFile.getAbsolutePath());

            Message msg = AdProtos.Ad.newBuilder()
                    .setId("32309719080")
                    .setTitle("some title")
                    .setStatus("ON")
                    .setLastUpdated("1470733203505")
                    .build();

            db.put("32309719080".getBytes(), msg.toByteArray());

            when(rocksDBManager.getDB(anyString())).thenReturn(db);

            Message msgFromKafka = AdProtos.Ad.newBuilder()
                    .setId("32309719080")
                    .setTitle("some updated title")
                    .setStatus("OFF")
                    .setLastUpdated("1480733203505")
                    .build();
            RocksDBExtractionNamespace extractionNamespace = new RocksDBExtractionNamespace(
                    "ad_lookup", "blah", "blah", new Period(), "", true, false, "ad_lookup", "last_updated", null
                    , "com.yahoo.maha.maha_druid_lookups.server.lookup.namespace.entity.NoopCacheActionRunner", null, false, true, 0, null);

            noopObj.getCachePopulator("ad_lookup", extractionNamespace, "32309719080", new HashMap<>());
            noopObj.updateCache(extractionNamespace, new HashMap<>(), "32309719080", msgFromKafka.toByteArray());
            byte[] cacheVal = noopObj.getCacheValue(extractionNamespace, new HashMap<>(), "32309719080", "", Optional.empty());
            Assert.assertNull(cacheVal);
        } finally {
            if(db != null) {
                db.close();
            }
            if(tempFile.exists()) {
                FileUtils.forceDelete(tempFile);
            }
        }
    }

    @Test
    public void testUpdateCacheWithLesserLastUpdated() throws Exception{

        Options options = null;
        RocksDB db = null;
        File tempFile = null;
        try {

            tempFile = new File(Files.createTempDir(), "rocksdblookup");
            options = new Options().setCreateIfMissing(true);
            db = RocksDB.open(options, tempFile.getAbsolutePath());

            Message msg = AdProtos.Ad.newBuilder()
                    .setId("32309719080")
                    .setTitle("some title")
                    .setStatus("ON")
                    .setLastUpdated("1470733203505")
                    .build();

            db.put("32309719080".getBytes(), msg.toByteArray());

            when(rocksDBManager.getDB(anyString())).thenReturn(db);

            RocksDBExtractionNamespace extractionNamespace = new RocksDBExtractionNamespace(
                    "ad_lookup", "blah", "blah", new Period(), "", true, false, "ad_lookup", "last_updated", null
            , "", null, false, true, 0, null);

            Message msgFromKafka = AdProtos.Ad.newBuilder()
                    .setId("32309719080")
                    .setTitle("some updated title")
                    .setStatus("OFF")
                    .setLastUpdated("1460733203505")
                    .build();

            obj.updateCache(extractionNamespace, new HashMap<>(), "32309719080", msgFromKafka.toByteArray());

            Parser<Message> parser = new TestProtobufSchemaFactory().getProtobufParser(extractionNamespace.getNamespace());
            Message updatedMessage = parser.parseFrom(db.get("32309719080".getBytes()));

            Descriptors.Descriptor descriptor = new TestProtobufSchemaFactory().getProtobufDescriptor(extractionNamespace.getNamespace());
            Descriptors.FieldDescriptor field = descriptor.findFieldByName("title");

            Assert.assertEquals(updatedMessage.getField(field).toString(), "some title");

            field = descriptor.findFieldByName("status");
            Assert.assertEquals(updatedMessage.getField(field).toString(), "ON");

            field = descriptor.findFieldByName("last_updated");
            Assert.assertEquals(updatedMessage.getField(field).toString(), "1470733203505");
            Assert.assertEquals(extractionNamespace.getLastUpdatedTime().longValue(), 1460733203505L);

        } finally {
            if(db != null) {
                db.close();
            }
            if(tempFile.exists()) {
                FileUtils.forceDelete(tempFile);
            }
        }
    }

    @Test
    public void testGetCacheValue() throws Exception{

        Options options = null;
        RocksDB db = null;
        File tempFile = null;
        try {

            tempFile = new File(Files.createTempDir(), "rocksdblookup");
            options = new Options().setCreateIfMissing(true);
            db = RocksDB.open(options, tempFile.getAbsolutePath());

            Message msg = AdProtos.Ad.newBuilder()
                    .setId("32309719080")
                    .setTitle("some title")
                    .setStatus("ON")
                    .setLastUpdated("1470733203505")
                    .build();

            db.put("32309719080".getBytes(), msg.toByteArray());

            when(rocksDBManager.getDB(anyString())).thenReturn(db);

            RocksDBExtractionNamespace extractionNamespace = new RocksDBExtractionNamespace(
                    "ad_lookup", "blah", "blah", new Period(), "", true, false, "ad_lookup", "last_updated", null
            , null, null, false, true, 0, null);

            obj.dynamicLookupSchemaManager.updateSchema(extractionNamespace, dynamicLookupSchema);

            byte[] value = obj.getCacheValue(extractionNamespace, new HashMap<>(), "32309719080", "title", Optional.empty());

            Assert.assertEquals(new String(value, UTF_8), "some title");

        } finally {
            if (db != null) {
                db.close();
            }
            if (tempFile.exists()) {
                FileUtils.forceDelete(tempFile);
            }
        }
    }

    @Test
    public void testGetCacheValueWhenNull() throws Exception{

        Options options = null;
        RocksDB db = null;
        File tempFile = null;
        try {

            tempFile = new File(Files.createTempDir(), "rocksdblookup");
            options = new Options().setCreateIfMissing(true);
            db = RocksDB.open(options, tempFile.getAbsolutePath());

            when(rocksDBManager.getDB(anyString())).thenReturn(db);

            RocksDBExtractionNamespace extractionNamespace = new RocksDBExtractionNamespace(
                    "ad_lookup", "blah", "blah", new Period(), "", true, false, "ad_lookup", "last_updated", null
            , null, null, false, false, 0, null);

            byte[] value = obj.getCacheValue(extractionNamespace, new HashMap<>(), "32309719080", "title", Optional.empty());

            Assert.assertEquals(value, new byte[0]);

        } finally {
            if(db != null) {
                db.close();
            }
            if(tempFile.exists()) {
                FileUtils.forceDelete(tempFile);
            }
        }
    }
}
