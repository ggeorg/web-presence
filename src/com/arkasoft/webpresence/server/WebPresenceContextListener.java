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
	private static final String TAG;

	static {
		LOG.setLogLevel(LOG.VERBOSE);
		LOG.setLogger(new Log4JLogger());

		TAG = LOG.tag(WebPresenceContextListener.class);
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
		return presence;
	}

	@Override
	protected void onConnectionOpen(DTalkServerEndpoint conn) {
		LOG.v(TAG, ">>> onConnectionOpen: %s", conn);

		// List of all connections grouped by remote address.
		List<DTalkServerEndpoint> conns;

		HandshakeRequest req = conn.getHandshakeRequest();
		Map<String, List<String>> requestParams = req.getParameterMap();
		LOG.d(TAG, "Request params: %s", requestParams);

		String remoteAddress = getRemoteAddress(requestParams);
		if (remoteAddress != null && remoteAddress.trim().length() > 0) {
			remoteAddress = remoteAddress.trim();

			// Get list of all connections grouped by remote address, create group if
			// no available.
			conns = connGroups.get(remoteAddress);
			if (conns == null) {
				synchronized (this) {
					conns = connGroups.get(remoteAddress);
					if (conns == null) {
						conns = new CopyOnWriteArrayList<DTalkServerEndpoint>();
						connGroups.put(remoteAddress, conns);
					}
				}
			}

			// Add new connection to connection group.
			synchronized (this) {
				conns.add(conn);
			}

		} else {
			LOG.w(TAG, "Can't get remote address. Closing connection...");
			conn.close();
			return;
		}

		// Send presence ADDED event to the presence list and send presence list to
		// the new presence.
		final JSONObject presence = getPresence(requestParams);
		onPresenceAdded(conns, conn, presence);
	}

	@Override
	protected void onConnectionClose(DTalkServerEndpoint conn) {
		LOG.v(TAG, ">>> onConnectionClose: %s", conn);

		// List of all connections grouped by remote address.
		List<DTalkServerEndpoint> conns;

		HandshakeRequest req = conn.getHandshakeRequest();
		Map<String, List<String>> requestParams = req.getParameterMap();
		LOG.d(TAG, "Request params: %s", requestParams);

		String remoteAddress = getRemoteAddress(requestParams);
		if (remoteAddress != null && remoteAddress.trim().length() > 0) {
			remoteAddress = remoteAddress.trim();

			// Get list of all connections grouped by remote address, create group if
			// no available.
			conns = connGroups.get(remoteAddress);
			if (conns != null) {
				// Remove the connection from the connection group.
				conns.remove(conn);

				// Destroy connection group if empty.
				if (conns.size() == 0) {
					synchronized (this) {
						if (conns.size() == 0) {
							connGroups.remove(remoteAddress);
						}
					}
				}

				// Send REMOVED event to the presence list.
				JSONObject presence = (JSONObject) conn.getSession().getUserProperties().get(PROP_DTALK_PRESENCE);
				if (presence != null) {
					onPresenceRemoved(conn, presence, remoteAddress);
				}
			}
		}
	}

	private void onPresenceAdded(List<DTalkServerEndpoint> conns, DTalkServerEndpoint conn, JSONObject presence) {
		LOG.v(TAG, ">>> onPresenceAdded: %s", presence);

		JSONObject event;

		//
		// If presence is 'null' just do Step: 3.
		//
		// Note: WebPages don't have presence.
		//

		if (presence != null) {

			// Store presence object & cleanup
			LOG.d(TAG, "Store new presence to user properties.");
			conn.getSession().getUserProperties().put(PROP_DTALK_PRESENCE, presence);

			//
			// 1. Check if there is another connection with the same name, if yes
			// close it.
			//

			final String name = presence.optString("name", null);
			if (conns != null && name != null) {
				for (DTalkServerEndpoint c : conns) {
					// skip current connection
					if (conn.equals(c)) {
						continue;
					}

					JSONObject _presence = (JSONObject) c.getSession().getUserProperties().get(PROP_DTALK_PRESENCE);
					if (_presence != null) {
						if (name.equals(_presence.optString("name", null))) {
							LOG.w(TAG, "Closing old connection with the same name: %s", name);
							// onConnectionClose(c);
							c.close();
						}
					}
				}
			}

			//
			// 2. Broadcast new presence.
			//

			LOG.d(TAG, "Broadcast new presence...");

			try {
				event = newPresenceAddedEvent(presence);
			} catch (JSONException e) {
				LOG.e(TAG, e.getMessage(), e);

				// Close new connection and return;
				conn.close();
				return;
			}

			// Loop over all connections and notify them about the new presence.
			for (DTalkServerEndpoint c : conns) {
				// don't send to current connection
				if (conn.equals(c)) {
					continue;
				}

				try {
					LOG.d(TAG, "Sending message to: %s", c.getSession().getId());
					sendMessage(c, event);
				} catch (Exception e) {
					LOG.w(TAG, e.getMessage(), e);

					// Close connection: c
					c.close();
				}
			}

		}

		//
		// 3. Send presence list to the new presence.
		//

		final JSONArray presences = new JSONArray();
		if (conns != null) {
			// Loop over all connections to create the presence list.
			for (DTalkServerEndpoint c : conns) {
				// don't put the new presence in the presence list.
				if (conn.equals(c)) {
					continue;
				}

				// Get connection presence object from the user properties.
				JSONObject p = (JSONObject) c.getSession().getUserProperties().get(PROP_DTALK_PRESENCE);
				if (p != null) {
					presences.put(p);
				}
			}
		}

		try {
			event = newPresenceAddedEvent(presences);
		} catch (JSONException e) {
			LOG.e(TAG, e.getMessage(), e);

			// Close new connection and return;
			conn.close();
			return;
		}

		try {
			sendMessage(conn, event);
		} catch (Exception e) {
			LOG.e(TAG, e.getMessage());

			// Close connection if error.
			conn.close();
		}
	}

	private void onPresenceRemoved(DTalkServerEndpoint conn, JSONObject presence,
			String remoteAddress) {

		//
		// Broadcast presence REMOVED.
		//

		JSONObject event;
		try {
			event = newPresenceRemovedEvent(presence);
		} catch (JSONException e) {
			LOG.e(TAG, e.getMessage(), e);

			// XXX Very bad! Can we handle this...
			return;
		}

		List<DTalkServerEndpoint> conns = connGroups.get(remoteAddress);
		if (conns != null) {
			for (DTalkServerEndpoint c : conns) {
				// don't send to current connection
				if (conn == c) {
					continue;
				}

				try {
					sendMessage(c, event);
				} catch (Exception e) {
					LOG.w(TAG, e.getMessage(), e);

					// Close connection: c
					c.close();
				}
			}
		}
	}

	private void sendMessage(final DTalkServerEndpoint conn, JSONObject message) throws JSONException {
		conn.sendMessage(message, new AsyncCallback<Boolean>() {
			@Override
			public void onFailure(Throwable caught) {
				caught.printStackTrace();

				// Close the connection...
				conn.close();
			}

			@Override
			public void onSuccess(Boolean result) {
				// TODO Auto-generated method stub
			}
		});
	}

	private JSONObject newPresenceAddedEvent(JSONObject presence) throws JSONException {
		JSONArray params = new JSONArray();
		params.put(presence);

		JSONObject event = new JSONObject();
		event.put(DTalk.KEY_BODY_VERSION, "1.0");
		event.put(DTalk.KEY_BODY_SERVICE, Constants.SERVICE_WEB_PRESENCE);
		event.put(DTalk.KEY_BODY_ACTION, Constants.SERVICE_WEB_PRESENCE_ADDED);
		event.put(DTalk.KEY_BODY_PARAMS, params);

		return event;
	}

	private JSONObject newPresenceAddedEvent(JSONArray presenceList) throws JSONException {
		JSONArray params = new JSONArray();
		params.put(presenceList);

		JSONObject event = new JSONObject();
		event.put(DTalk.KEY_BODY_VERSION, "1.0");
		event.put(DTalk.KEY_BODY_SERVICE, Constants.SERVICE_WEB_PRESENCE);
		event.put(DTalk.KEY_BODY_ACTION, Constants.SERVICE_WEB_PRESENCE_ADDED);
		event.put(DTalk.KEY_BODY_PARAMS, params);

		return event;
	}

	private JSONObject newPresenceRemovedEvent(JSONObject presence) throws JSONException {
		JSONObject event = new JSONObject();
		event.put(DTalk.KEY_BODY_VERSION, "1.0");
		event.put(DTalk.KEY_BODY_SERVICE, Constants.SERVICE_WEB_PRESENCE);
		event.put(DTalk.KEY_BODY_ACTION, Constants.SERVICE_WEB_PRESENCE_REMOVED);
		event.put(DTalk.KEY_BODY_PARAMS, presence);
		return event;
	}

	@Override
	public void runOnUiThread(Runnable r) {
		r.run();
	}

}
