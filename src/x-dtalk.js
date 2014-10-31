(function() {
	if (!window.XDTalk) {
		var TOPIC_XDTALK_MSGIN = "x-dtalk.msgIn";
		var TOPIC_XDTALK_MSGOUT = "x-dtalk.msgOut";
		
		window.XDTalk = {
			_ws : null,
			_presences : null,
			_connections : null,
			init : function() {
				this._presences = this._connections = [];
				
				if (!window.WebSocket) {
					window.WebSocket = window.MozWebSocket;
				}

				if (window.WebSocket) {
					this._ws = new WebSocket("@WEB_PRESENCE_SRV@");
					this._ws.onopen = function() {
						//alert('presence opended...');
					};
					this._ws.onmessage = function(evt) {
						try {
							evt = JSON.parse(evt.data);
							if (evt && "dtalk.Presence" === evt.service) {
								if ("added" === evt.action) {
									var items = evt.params;
									if (items && items.length) {
										for (var i = 0, n = items.length; i < n; i++) {
											//------------- remove item
											var name = items[i].name;
											var itemIdx = -1;
											for (var j = 0, m = XDTalk._presences.length; j < m; j++) {
												if (name === XDTalk._presences[j].name) {
													itemIdx = j;
													break;
												}
											}
											if (itemIdx >= 0) {
												XDTalk._presences.splice(itemIdx, 1);
											}
											//------------- remove item
											
											XDTalk._presences.push(items[i]);
											
											// fire presence event...
											XDTalk.fireEvent("$dtalk.service.Presence.resolved", items[i]); // TODO remove this
											XDTalk.fireEvent("$dtalk.service.Presence.onresolved", items[i]);
											
											//alert("resolved: " + JSON.stringify(items[i]));
										}
									}
								} else if ("removed" === evt.action) {
									var item = evt.params;
									if (item && item.name && XDTalk._presences.length) {
										var name = item.name;
										var itemIdx = -1;
										for (var i = 0, n = XDTalk._presences.length; i < n; i++) {
											if (name === XDTalk._presences[i].name) {
												itemIdx = i;
												break;
											}
										}
										if (itemIdx >= 0) {
											XDTalk._presences.splice(itemIdx, 1);
											
											// fire presence event...
											XDTalk.fireEvent("$dtalk.service.Presence.removed", item); // TODO remove this
											XDTalk.fireEvent("$dtalk.service.Presence.onremoved", item);
											
											//alert("removed: " + JSON.stringify(item));
										}
									}
								}
							}
						} catch(e) {
							console.error(e);
						}
					};
					this._ws.onclose = function() {
						// alert('presence disconnected...');
						for (var i = 0, n = XDTalk._presences.length; i < n; i++) {
							var item = XDTalk._presences[i];
							
							// fire presence event...
							XDTalk.fireEvent("$dtalk.service.Presence.removed", item);
							
							//alert("removed: " + JSON.stringify(item));
						}
						setTimeout(function() {
							XDTalk._reconnect();
						}, 3333);
					};
				}
			},
			
			_reconnect: function() {
				this.init();
			},

			send : function(message) {
				console.log(">>> send: " + message);
				try {
					message = JSON.parse(message);
					if (message && "to" in message) {
						// send outgoing message...
						DTalk.publish(TOPIC_XDTALK_MSGOUT, message);
					} else {
						// send incoming message...
						DTalk.publish(TOPIC_XDTALK_MSGIN, message);
					}
				} catch(e) {
					console.error(e);
				}
			},
			
			addEventListener : function(event, listener) {
				try {
					window.addEventListener(event, listener, false);
				} catch (e) {
					console.error(e);
				}
			},
			
			// EVENT API
			
			fireEvent : function(event, data) {
				var evt = {
					dtalk: "1.0",
					service: event
				};
				if (data) {
					evt.params = data;
				}
				DTalk.publish(event, evt);
			},
			
			// RESPONSE API
			
			newResponse : function(req) {
				var res = null;
				if ("id" in req) {
					res = {
						dtalk: "1.0",
						service: req.id
					};
					// if ("from" in req) {
					// res.to = req.from;
					//					}
				}
				return res;
			},
			
			sendResponse : function(req, data) {
				var res = this.newResponse(req);
				if (res) {
					res.result = data;
					if ("to" in res) {
						// TODO remove to & from
						DTalk.publish(TOPIC_XDTALK_MSGOUT, res);
					} else {
						DTalk.publish(res.service, res);
					}
				}
			},
			
			showRoster : function(filter, callback) {
				DTalk.doGet('dtalk.service.Presence', 'roster', function(e) {
					var roster = e.data.result;
					
					var iframe = document.getElementById('x-dtalk-iframe');
					if (!iframe) {
						iframe = document.createElement("iframe");

						iframe.setAttribute("frameborder", 0);
						iframe.setAttribute("src", 
								"@WEB_PRESENCE_SRV@".replace(/^ws/, 'http').replace(/dtalksrv$/, '') +
								"iframe-roster.html");
						
						var style = iframe.style;
						style.position = "absolute";
						style.zIndex = -1;
						style.top = "0";
						style.left = "0";
						style.width = "100%";
						style.height = "100%";
						style.backgroundColor = "#fff";
						style.visibility = "hidden";
						
						// Get a reference to the first child
						var theFirstChild = document.body.firstChild;
						if (theFirstChild) {
							// Insert the new element before the first child
							document.body.insertBefore(iframe, theFirstChild);
						} else {
							document.body.append(iframe);
						}
						
						window.addEventListener("message", function(e) {
							// Do we trust the sender of this message?
							//if (e.origin === VCA._origin) {
								style.zIndex = -1;
								style.visibility = "hidden";
								callback.call(XDTalk, e);
							//}
						});
					}
					
					var style = iframe.style;
					style.zIndex = 999999999;
					style.visibility = "visible";
				});
			}
		};
		
		// incoming message handler
		XDTalk.addEventListener(TOPIC_XDTALK_MSGIN, function(e) {
			var message = e.data;
			if (message && message.service) {
				DTalk.publish(message.service, message);
			}
		});
		
		// outgoing message handler
		XDTalk.addEventListener(TOPIC_XDTALK_MSGOUT, function(e) {
			var message = e.data;
			if (message && message.service && message.to) {
				var to = message.to;
				
				// add 'from' into message
				message.from = "x-dtalk-@UUID@";
				
				// search for open connections
				var conns = XDTalk._connections;
				var removeConn = -1;
				for (var i = 0, n = conns.length; i < n; i++) {
					var conn = conns[i];
					if (conn._name == to) {
						var msgStr = JSON.stringify(message);
						if (conn.readyState == 0) {
							conn._buffer.push(msgStr); // user buffer
						} else if (conn.readyState == 1) {
							conn.send(msgStr);
							return;
						} else {
							removeConn = i;
							break;
						}
					}
				}
				if (removeConn >= 0) {
					XDTalk._connections.splice(removeConn, 1);
				}
				
				// open a new connection
				var presences = XDTalk._presences;
				for (var i = 0, n = presences.length; i < n; i++) {
					var presence = presences[i];
					if (presence.name == to) {
						var msgStr = JSON.stringify(message);
						var url = presence.server;
						//"ws://" + presence.server + ":" + presence.port + "/dtalksrv";
						var conn = new WebSocket(url);
						conn._name = to;
						conn._buffer = [ msgStr ];
						conn.onopen = function() {
							for (var j = 0, m = conn._buffer.length; j < m; j++) {
								try {
									conn.send(conn._buffer[j]);
								} catch(e) {
									console.error(e);
								}
							}
							conn._buffer = []; // clear
						};
						conn.onmessage = function(evt) {
							try {
								var msg = JSON.parse(evt.data);
								DTalk.publish(msg.service, msg);
							} catch (e) {
								console.error(e);
							}
						};
						XDTalk._connections.push(conn);
						break;
					}
				}
			}
		});
		
		XDTalk.serviceMgr = {
			services: [],
			init: function() {
				XDTalk.addEventListener("dtalk.Services", function(e) {
					if (e && e.data) {
						if ("start" === e.data.action) {
							var name = e.data.params;
							console.log("START: " + name); // not implemented
							XDTalk.sendResponse(e.data, true);
						} else if ("stop" === e.data.action) {
							var name = e.data.params;
							console.log("STOP: " + name); // not implemented
						}
					}
				});
			},
			register: function(service) {
				if (service && service.name) {
					console.log("REGISTER: " + service.name);
					XDTalk.addEventListener(service.name, function(e) {
						if (e && e.data && e.data.action) {
							var action = e.data.action;
							switch(action) {
							case "get":
								var getter = "get_" + e.data.params;
								if (getter in service) {
									service[getter].call(service, e.data);
								}
								break;
							case "set":
								var properties = e.data.params;
								for (var p in properties) {
									var setter = "set_" + p;
									if (setter in service) {
										service[setter].call(service, e.data);
									}
								}
								break;
							default:
								var method = "do_" + e.data.action;
								if (method in service) {
									service[method].call(service, e.data);
								}
								break;
							}
						}
					});
				}
				if (typeof service.init === "function") {
					service.init.apply(service);
				}
			}
		};
		
		XDTalk.serviceMgr.init();
		XDTalk.init();
	}
})();

(function() {
	if(XDTalk) {
		XDTalk.serviceMgr.register({
			name: "dtalk.service.Presence",
			init: function() {
				// do nothing...
			},
			get_list: function(request) {
				XDTalk.sendResponse(request, XDTalk._presences);
			},
			get_roster: function(request) {
				XDTalk.sendResponse(request, XDTalk._presences);
			},
		});
	}
})();