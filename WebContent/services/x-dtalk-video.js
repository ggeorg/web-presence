(function() {
	if(XDTalk) {
		XDTalk.serviceMgr.register({
			name: "dtalk.service.Video",
			_video: null,
			_timerH: null,
			_startPositionPercent: 0,
			init: function() {
				var video = document.querySelector("#x-dtalk-video");
				if (!video) {
					video = document.createElement("video");
					video.id = "x-dtalk-video";
					video.style.position = "absolute";
					video.style.top = video.style.left = "0px";
					video.style.width = video.style.height = "100%";
					video.style.zIndex = -9999;
				}
				this._video = video;
				
				var self = this;
				window.addEventListener("load", function() {
					var body = document.querySelector("body");
					body.appendChild(self._video);
				});
				
				this._video.addEventListener("loadedmetadata", function(e) {
					//alert("Event: loadedmetadata");

					if (self._startPositionPercent > 0 && self._startPositionPercent < 1) {
						try {
							self._video.currentTime = self._startPositionPercent * self._video.duration;
						} catch(e) {
							console.error(e);
						}
						self._startPositionPercent = 0;
					}
					XDTalk.fireEvent("$dtalk.service.Video.onprepared");
				});
				
				this._video.addEventListener("ended", function(e) {
					//alert("Event: ended");

					self._stopTimer();
					XDTalk.fireEvent("$dtalk.service.Video.oncompletion");
				});
				
				this._video.addEventListener("error", function(e) {
					var msg;
					switch (e.target.error.code) {
					case e.target.error.MEDIA_ERR_ABORTED:
						msg = 'You aborted the video playback.';
						break;
					case e.target.error.MEDIA_ERR_NETWORK:
						msg = 'A network error caused the video download to fail part-way.';
						break;
					case e.target.error.MEDIA_ERR_DECODE:
						msg = 'The video playback was aborted due to a corruption problem or because the video used features your browser did not support.';
						break;
					case e.target.error.MEDIA_ERR_SRC_NOT_SUPPORTED:
						msg = 'The video could not be loaded, either because the server or network failed or because the format is not supported.';
						break;
					default:
						msg = 'An unknown error occurred.';
						break;
					}
					console.debug("Error: " + msg);
					XDTalk.fireEvent("$dtalk.service.Video.onerror", msg);
					self._stopTimer();
				});
			},
			get_src: function(request) {
				XDTalk.sendResponse(request, this._video.src);
			},
			set_src: function(event) {
				var src = event.params.src;
				if (src) {
					this._video.src = src;
					this._video.load();
					this._startTimer();
				}
			},
			set_startPositionPercent: function(event) {
				var value = event.params.startPositionPercent;
				if (!isNaN(value)) {
					this._startPositionPercent = value;
				}
			},
			get_info: function(request) {
				this.sendResponse(request, this._getStatus());
			},
			do_play: function() {
				this._video.play();
				this._sendStatus();
				this._startTimer();
			},
			do_stop: function() {
				this._video.pause();
				this._stopTimer();
				XDTalk.fireEvent("$dtalk.service.Video.oncompletion");
			},
			do_pause: function() {
				this._video.pause();
				this._sendStatus();
			},
			do_seekTo: function(event) {
				this._video.currentTime = event.params;
				this._sendStatus();
			},
			do_setRate: function(event) {
				var value = event.params;
				if (!isNaN(value)) {
					if (value === 0) {
						this.do_pause();
					} else {
						this.do_play();
					}
				}
			},
			_getStatus: function() {
				var status = {
					src: this._video.src,
					duration: this._video.duration,
					position: this._video.currentTime,
					paused: this._video.paused
				};
				// TODO: volume, canPause, bufferedPercent
				return status;
			},
			_sendStatus: function() {
				XDTalk.fireEvent("$dtalk.service.Video.onstatus", this._getStatus());
			},
			_startTimer: function() {
				var self = this;
				if (!self.timerH) {
					self.timerH = setInterval(function() {
						self._sendStatus();
					}, 1000);
				}
				// else: reuse old timer  
			},
			_stopTimer: function() {
				if (this.timerH) {
					this._sendStatus();
					clearInterval(this.timerH);
					this.timerH = null;
				}
			}
		});
	}
})();