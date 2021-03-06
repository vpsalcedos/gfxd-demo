/*==========================================================================
 * Copyright (c) 2014 Pivotal Software Inc. All Rights Reserved.
 *
 * This product is licensed to you under the Apache License, Version 2.0 (the "License").
 * You may not use this product except in compliance with the License.
 *
 * This product may include a number of subcomponents with separate copyright
 * notices and license terms. Your use of these subcomponents is subject to
 * the terms and conditions of the subcomponent's license, as noted in the
 * LICENSE file.
 *==========================================================================
 */

package com.pivotal.gfxd.demo;

import com.pivotal.gemfirexd.callbacks.AsyncEventListener;
import com.pivotal.gemfirexd.callbacks.Event;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * @author Jens Deppe
 */
public class AggregationListener implements AsyncEventListener {

  private static final Logger LOG = LoggerFactory.getLogger(
      AggregationListener.class.getName());

  private static final String DRIVER = "com.pivotal.gemfirexd.jdbc.ClientDriver";

  private static final String CONN_URL = "jdbc:gemfirexd:";

  private static final String SELECT_SQL = "select * from load_averages where weekday=? and time_slice=? and plug_id=?";

  private static final String UPDATE_SQL = "update load_averages set total_load=?, event_count=? where weekday=? and time_slice=? and plug_id=?";

  private String valueColumn;

  /*
   * See {@link #close()}
   */
  private static List<Connection> connections = new ArrayList<>();

  //load driver
  static {
    try {
      Class.forName(DRIVER).newInstance();
    } catch (ClassNotFoundException cnfe) {
      throw new RuntimeException("Unable to load the JDBC driver", cnfe);
    } catch (InstantiationException ie) {
      throw new RuntimeException("Unable to instantiate the JDBC driver", ie);
    } catch (IllegalAccessException iae) {
      throw new RuntimeException("Not allowed to access the JDBC driver", iae);
    }
  }

  private static ThreadLocal<Connection> dConn = new ThreadLocal<Connection>() {
    protected Connection initialValue() {
      return getConnection();
    }
  };

  private static Connection getConnection() {
    Connection conn;
    try {
      conn = DriverManager.getConnection(CONN_URL);
    } catch (SQLException e) {
      throw new IllegalStateException("Unable to create connection", e);
    }
    connections.add(conn);
    return conn;
  }

  private static ThreadLocal<PreparedStatement> selectStmt = new ThreadLocal<PreparedStatement> () {
    protected PreparedStatement initialValue()  {
      PreparedStatement stmt = null;
      try {
        stmt = dConn.get().prepareStatement(SELECT_SQL);
      } catch (SQLException se) {
        throw new IllegalStateException("Unable to retrieve statement ", se);
      }
      return stmt;
    }
  };

  private static ThreadLocal<PreparedStatement> updateStmt = new ThreadLocal<PreparedStatement> () {
    protected PreparedStatement initialValue()  {
      PreparedStatement stmt = null;
      try {
        stmt = dConn.get().prepareStatement(UPDATE_SQL);
      } catch (SQLException se) {
        throw new IllegalStateException("Unable to retrieve statement ", se);
      }
      return stmt;
    }
  };

  @Override
  public boolean processEvents(List<Event> events) {
    for (Event e : events) {
      if (e.getType() == Event.Type.AFTER_INSERT) {
        ResultSet eventRS = e.getNewRowsAsResultSet();
        try {
          PreparedStatement s = selectStmt.get();
          s.setInt(1, eventRS.getInt("weekday"));
          s.setInt(2, eventRS.getInt("time_slice"));
          s.setInt(3, eventRS.getInt("plug_id"));
          ResultSet queryRS = s.executeQuery();

          if (queryRS.next()) {
            PreparedStatement update = updateStmt.get();
            update.setFloat(1,
                queryRS.getFloat("total_load") + eventRS.getFloat(valueColumn));
            update.setInt(2, queryRS.getInt("event_count") + 1);
            update.setInt(3, queryRS.getInt("weekday"));
            update.setInt(4, queryRS.getInt("time_slice"));
            update.setInt(5, queryRS.getInt("plug_id"));
            update.executeUpdate();
          }
        } catch (SQLException ex) {
          ex.printStackTrace();
        }
      }
    }
    return true;
  }

  @Override
  public void close() {
    // Close all our previously created connections. This works around
    // Trac #50091.
    for (Connection c : connections) {
      try {
        c.close();
      } catch (SQLException ex) {
        // Ignored - we're shutting down.
      }
    }
  }

  @Override
  public void init(String s) {
    valueColumn = s;
  }

  @Override
  public void start() {
  }
}
