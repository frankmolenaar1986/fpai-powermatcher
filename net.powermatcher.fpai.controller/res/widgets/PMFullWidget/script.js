$(window).load(function() {
	w = new widget("update", 5000, function(data) {
		$(".error").hide();
		$("#marketprice").text(data.marketPrice);
		for(var i in data.agents) {
			var id = data.agents[i].id;
			var div = $("#bid-"+i);
			if(div.size() == 0) {
				$("#bids").append('<div id="bid-'+i+'"><p></p><div style="width: 400px; height: 100px;"></div></div>');
				var div = $("#bid-"+i);
			}
			div.find("p").text(id);
			$.plot("#bid-"+i+" div", [data.agents[i].coordinates, [[data.marketPrice,-2000],[data.marketPrice,2000]]]);
		}
	});
	
	w.error = function(msg) {
		$(".error").show();
		$(".error").text(msg);
	}
});