$(window).load(function() {
	w = new widget("update", 1000, function(data) {
		$("#loading").detach();
		$(".error").hide();
		$("#marketprice").text(data.marketPrice);
		$("#timestamp").text(data.timestamp);
		
		$("#agents").empty();
		
		for(type in data.demands){
			i = 1;
			for(id in data.demands[type]){
				$("#agents").append("<p><label>"+ type +" "+ i +"</label> <span>" + data.demands[type][id] + "</span></p>");
				i++;
			}
		}
		
		$("p").show();
	});
	
	w.error = function(msg) {
		$("#loading").detach();
		// $("p").hide();
		$(".error").show();
		$(".error").text(msg);
	}
});