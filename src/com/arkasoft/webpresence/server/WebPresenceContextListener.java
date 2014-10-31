package com.arkasoft.webpresence.server;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.servlet.ServletContextEvent;
import javax.servlet.annotation.WebListener;
import javax.websocket.server.HandshakeRequest;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.arkasoft.freddo.dtalk.j7ee.server.DTalkContextListener;
import com.arkasoft.freddo.dtalk.j7ee.server.DTalkServerEndpoint;

import freddo.dtalk.DTalk;
import freddo.dtalk.services.FdServiceMgr;
import freddo.dtalk.util.AsyncCallback;
import freddo.dtalk.util.Base64;
import freddo.dtalk.util.LOG;
import freddo.dtalk.util.Log4JLogger;

@WebListener
public class WebPresenceContextListener extends DTalkContextListener {
  private static final String TAG = LOG.tag(WebPresenceContextListener.class);

  static {
    LOG.setLogLevel(LOG.VERBOSE);
    LOG.setLogger(new Log4JLogger());
  }

  private static final String PROP_DTALK_PRESENCE = "dtalk-presence";

  private final Map<String, List<DTalkServerEndpoint>> connGroups = new ConcurrentHashMap<String, List<DTalkServerEndpoint>>();

  @SuppressWarnings("unused")
  private FdServiceMgr mServiceMgr = null;

  private String mRemoteAddrPolicy;

  @Override
  public void contextInitialized(ServletContextEvent sce) {
    LOG.v(TAG, ">>> contextInitialized");
    super.contextInitialized(sce);

    // Read remote address policy from web.xml
    mRemoteAddrPolicy = sce.getServletContext().getInitParameter(CONFIG_REMOTE_ADDR_POLICY);
    if (mRemoteAddrPolicy != null) {
      mRemoteAddrPolicy = mRemoteAddrPolicy.toLowerCase();
    }

    // Create service manager and initialize services...
    mServiceMgr = new FdServiceMgr(this);
    // mServiceMgr.registerService(new FdStoreFactory());

    // addService(Constants.SERVICE_WEB_PRESENCE, new WebPresenceService());
  }

  protected String getRemoteAddress(Map<String, List<String>> requestParams) {
    LOG.v(TAG, ">>> getRemoteAddress");
    String remoteAddress = null;
    if ("true".equalsIgnoreCase(mRemoteAddrPolicy)) {
      List<String> remoteAddressParam = requestParams.get("dtalk-remote-address");
      if (remoteAddressParam != null && remoteAddressParam.size() > 0) {
        remoteAddress = remoteAddressParam.get(0);
      }
    } else {
      remoteAddress = "common";
    }
    LOG.d(TAG, "RemoteAddress: %s", remoteAddress);
    return remoteAddress;
  }

  protected JSONObject getPresence(Map<String, List<String>> requestParams) {
    LOG.v(TAG, ">>> getPresence");
    List<String> presenceParam = requestParams.get("presence");
    JSONObject presence = null;
    if (presenceParam != null && presenceParam.size() > 0) {
      String presenceStr = presenceParam.get(0);
      if (presenceStr != null) {
        byte[] bytesOfDecodedPresenceStr = null;
        try {
          bytesOfDecodedPresenceStr = Base64.decode(presenceStr);
        } catch (IOException e1) {
          LOG.e(TAG, e1.getMessage());
        }
        if (bytesOfDecodedPresenceStr != null && bytesOfDecodedPresenceStr.length > 0) {
          presenceStr = new String(bytesOfDecodedPresenceStr);
          if (presenceStr != null && presenceStr.trim().length() > 0) {
            try {
              presence = new JSONObject(presenceStr);
            } catch (JSONException e) {
              LOG.e(TAG, e.getMessage());
            }
          }
        }
      }
    }
    LOG.d(TAG, "Presence: %s", presence);
    return presence;
  }

  @Override
  protected void onConnectionOpen(DTalkServerEndpoint conn) {
    LOG.v(TAG, ">>> onConnectionOpen: %s", conn.getSession().getRequestURI());

    HandshakeRequest req = conn.getHandshakeRequest();
    Map<String, List<String>> requestParams = req.getParameterMap();

    String remoteAddress = getRemoteAddress(requestParams);
    if (remoteAddress != null && remoteAddress.trim().length() > 0) {
      remoteAddress = remoteAddress.trim();
      // synchronized (this) {
      List<DTalkServerEndpoint> conns = connGroups.get(remoteAddress);
      if (conns == null) {
        conns = new CopyOnWriteArrayList<DTalkServerEndpoint>();
        connGroups.put(remoteAddress, conns);
      }

      LOG.d(TAG, "Connections for %s: %s", remoteAddress, conns);
      LOG.d(TAG, "Adding new connection for %s: %s", remoteAddress, conn);

      conns.add(conn);

      // }

    } else {

      LOG.d(TAG, "Can't get remote address. Closing connection...");

      try {
        conn.getSession().close();
      } catch (Exception e) {
        // Ignore
      }
      return;
    }

    //
    // Send presence ADDED event...
    //

    JSONObject presence = getPresence(requestParams);
    if (presence != null) {
      onPresenceAdded(conn, presence, remoteAddress);
    }

    //
    // Send presences list...
    //

    if (presence == null) {
      JSONArray presences = new JSONArray();
      List<DTalkServerEndpoint> conns = connGroups.get(remoteAddress);
      if (conns != null) {
        for (DTalkServerEndpoint c : conns) {
          if (conn.equals(c)) {
            continue;
          }

          JSONObject p = (JSONObject) c.getSession().getUserProperties().get(PROP_DTALK_PRESENCE);
          if (p != null) {
            presences.put(p);
          }
        }
      }

      try {
        JSONObject event = new JSONObject();
        event.put(DTalk.KEY_BODY_VERSION, "1.0");
        event.put(DTalk.KEY_BODY_SERVICE, Constants.SERVICE_WEB_PRESENCE);
        event.put(DTalk.KEY_BODY_ACTION, Constants.SERVICE_WEB_PRESENCE_ADDED);
        event.put(DTalk.KEY_BODY_PARAMS, presences);
        sendMessage(conn, event);
      } catch (Exception e) {
        LOG.e(TAG, e.getMessage());
      }
    }
  }

  private void sendMessage(DTalkServerEndpoint conn, JSONObject message) throws JSONException {
    conn.sendMessage(message, new AsyncCallback<Boolean>() {
      @Override
      public void onFailure(Throwable caught) {
        caught.printStackTrace();
      }

      @Override
      public void onSuccess(Boolean result) {
        // TODO Auto-generated method stub
      }
    });
  }

  @Override
  protected void onConnectionClose(DTalkServerEndpoint conn) {
    LOG.v(TAG, ">>> onConnectionClose");

    HandshakeRequest req = conn.getHandshakeRequest();
    Map<String, List<String>> requestParams = req.getParameterMap();

    String remoteAddress = getRemoteAddress(requestParams);
    if (remoteAddress != null && remoteAddress.trim().length() > 0) {
      remoteAddress = remoteAddress.trim();
      // synchronized (this) {
      List<DTalkServerEndpoint> conns = connGroups.get(remoteAddress);
      if (conns != null) {

        LOG.d(TAG, "Removing connection for %s: %s", remoteAddress, conn);

        conns.remove(conn);
        if (conns.size() == 0) {
          connGroups.remove(remoteAddress);
        }

        LOG.d(TAG, "Connections for %s: %s", remoteAddress, conns);

        // notify others...
        JSONObject presence = (JSONObject) conn.getSession().getUserProperties().get(PROP_DTALK_PRESENCE);
        if (presence != null) {
          onPresenceRemoved(conn, presence, remoteAddress);
        }
      }
      // }
    }
  }

  protected void resetConnections() {
    connGroups.clear();
  }

  private void onPresenceAdded(DTalkServerEndpoint conn, JSONObject presence, String remoteAddress) {
    LOG.v(TAG, ">>> onPresenceAdded: %s, remoteAddress: %s", presence, remoteAddress);

    //
    // 1. Store presence object & cleanup
    //

    LOG.d(TAG, "Store new presence...");

    conn.getSession().getUserProperties().put(PROP_DTALK_PRESENCE, presence);

    // Get list of all connections grouped by remote address
    List<DTalkServerEndpoint> conns = connGroups.get(remoteAddress);
    if (conns != null) {
      for (DTalkServerEndpoint c : conns) {
        // skip current connection
        if (conn.equals(c)) {
          continue;
        }

        JSONObject _presence = (JSONObject) c.getSession().getUserProperties().get(PROP_DTALK_PRESENCE);
        if (_presence != null) {
          String name = presence.optString("name", null);
          if (name != null && name.equals(_presence.optString("name", null))) {
            onConnectionClose(conn);
          }
        }
      }
    }

    //
    // 2. Broadcast new presence...
    //

    LOG.d(TAG, "Broadcast new presence...");

    JSONObject event = new JSONObject();

    try {
      event.put(DTalk.KEY_BODY_VERSION, "1.0");
      event.put(DTalk.KEY_BODY_SERVICE, Constants.SERVICE_WEB_PRESENCE);
      event.put(DTalk.KEY_BODY_ACTION, Constants.SERVICE_WEB_PRESENCE_ADDED);
      JSONArray params = new JSONArray();
      params.put(presence);
      event.put(DTalk.KEY_BODY_PARAMS, params);
    } catch (JSONException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }

    if (conns != null) {
      // String message = event.toString();

      // LOG.d(TAG, "message: %s", message);

      for (DTalkServerEndpoint c : conns) {
        // don't send to current connections
        if (conn.equals(c)) {
          continue;
        }
        try {
          LOG.d(TAG, "Sending message to: %s", c.getSession().getId());
          sendMessage(c, event);
        } catch (Exception e) {
          // TODO Auto-generated catch block
          e.printStackTrace();
        }
      }

    } else {
      // SHOULD NEVER HAPPEN!!!
      LOG.d(TAG, "No connections found for: %s.", remoteAddress);
    }

  }

  private void onPresenceRemoved(DTalkServerEndpoint conn, JSONObject presence,
      String remoteAddress) {

    //
    // 1. broadcast presence removed...
    //

    JSONObject event = new JSONObject();

    try {
      event.put(DTalk.KEY_BODY_VERSION, "1.0");
      event.put(DTalk.KEY_BODY_SERVICE, Constants.SERVICE_WEB_PRESENCE);
      event.put(DTalk.KEY_BODY_ACTION, Constants.SERVICE_WEB_PRESENCE_REMOVED);
      event.put(DTalk.KEY_BODY_PARAMS, presence);
    } catch (JSONException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }

    List<DTalkServerEndpoint> conns = connGroups.get(remoteAddress);
    if (conns != null) {
      for (DTalkServerEndpoint c : conns) {
        if (conn == c) {
          continue;
        }

        try {
          sendMessage(c, event);
        } catch (Exception e) {
          // TODO Auto-generated catch block
          e.printStackTrace();
        }
      }
    }
  }

  @Override
  public void runOnUiThread(Runnable r) {
    r.run();
  }

}
