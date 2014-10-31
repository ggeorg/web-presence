(function() {

	if(window.FdTelephony) {
		return;
	}
	
	window.FdTelephony = {
			
		textIt: function(id) {
			var elem = document.getElementById(id);
			if(!elem) return;
			
			XDTalk.showRoster('Telephony/1', function(e) {
				var data = e.data;
				if (data.response === "selection") {
					if (data.params && data.params.name) {
						DTalk.doAction("dtalk.service.Messaging", "send", {
							phoneNo: [],
							message: elem.innerHTML
						}, null, data.params.name);
					}
				}
			});
		},
		
		dialIt: function(id) {
			var elem = document.getElementById(id);
			if(!elem) return;
			
			XDTalk.showRoster('Telephony/1', function(e) {
				var data = e.data;
				if (data.response === "selection") {
					if (data.params && data.params.name) {
						DTalk.doAction("dtalk.service.Dialer", "dial", elem.innerHTML, null, data.params.name);
					}
				}
			});
		}
			
	};

})();