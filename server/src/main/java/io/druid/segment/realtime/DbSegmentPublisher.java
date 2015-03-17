/*
 * Druid - a distributed column store.
 * Copyright 2012 - 2015 Metamarkets Group Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.druid.segment.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.metamx.common.logger.Logger;
import io.druid.db.DbConnector;
import io.druid.db.DbTablesConfig;
import io.druid.timeline.DataSegment;
import io.druid.timeline.partition.NoneShardSpec;
import org.joda.time.DateTime;
import org.skife.jdbi.v2.Handle;
import org.skife.jdbi.v2.IDBI;
import org.skife.jdbi.v2.tweak.HandleCallback;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public class DbSegmentPublisher implements SegmentPublisher
{
  private static final Logger log = new Logger(DbSegmentPublisher.class);

  private final ObjectMapper jsonMapper;
  private final DbTablesConfig config;
  private final IDBI dbi;
  private final String statement;

  @Inject
  public DbSegmentPublisher(
      ObjectMapper jsonMapper,
      DbTablesConfig config,
      IDBI dbi
  )
  {
    this.jsonMapper = jsonMapper;
    this.config = config;
    this.dbi = dbi;

    if (DbConnector.isPostgreSQL(dbi)) {
      this.statement = String.format(
          "INSERT INTO %s (id, dataSource, created_date, start, \"end\", partitioned, version, used, payload) "
              + "VALUES (:id, :dataSource, :created_date, :start, :end, :partitioned, :version, :used, :payload)",
          config.getSegmentsTable()
      );
    } else {
      this.statement = String.format(
          "INSERT INTO %s (id, dataSource, created_date, start, end, partitioned, version, used, payload) "
              + "VALUES (:id, :dataSource, :created_date, :start, :end, :partitioned, :version, :used, :payload)",
          config.getSegmentsTable()
      );
    }
  }

  public void publishSegment(final DataSegment segment) throws IOException
  {
    try {
      List<Map<String, Object>> exists = dbi.withHandle(
          new HandleCallback<List<Map<String, Object>>>()
          {
            @Override
            public List<Map<String, Object>> withHandle(Handle handle) throws Exception
            {
              return handle.createQuery(
                  String.format("SELECT id FROM %s WHERE id=:id", config.getSegmentsTable())
              )
                           .bind("id", segment.getIdentifier())
                           .list();
            }
          }
      );

      if (!exists.isEmpty()) {
        log.info("Found [%s] in DB, not updating DB", segment.getIdentifier());
        return;
      }

      dbi.withHandle(
          new HandleCallback<Void>()
          {
            @Override
            public Void withHandle(Handle handle) throws Exception
            {
              handle.createStatement(statement)
                    .bind("id", segment.getIdentifier())
                    .bind("dataSource", segment.getDataSource())
                    .bind("created_date", new DateTime().toString())
                    .bind("start", segment.getInterval().getStart().toString())
                    .bind("end", segment.getInterval().getEnd().toString())
                    .bind("partitioned", (segment.getShardSpec() instanceof NoneShardSpec) ? 0 : 1)
                    .bind("version", segment.getVersion())
                    .bind("used", true)
                    .bind("payload", jsonMapper.writeValueAsBytes(segment))
                    .execute();

              return null;
            }
          }
      );
    }
    catch (Exception e) {
      log.error(e, "Exception inserting into DB");
      throw new RuntimeException(e);
    }
  }
}