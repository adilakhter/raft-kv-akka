/**
 * Created by mw on 04/05/17.
 *
 * Frontend js
 */
var servers = [];
var messages = [];

var Server = class {
    constructor(id, state, term, commitIndex, lastApplied, values, message) {
        this.id = id;
        this.state = state;
        this.term = term;
        this.commitIndex = commitIndex;
        this.lastApplied = lastApplied;
        this.values = values;
        this.message = message;
        this.counter = server_timeout_counter;
    }
};

var Message = class {
    constructor(name, value) {
        this.name = name;
        this.value = value;
        this.counter = message_delete_counter;
    }
};

var message_delete_counter = 10;
var server_timeout_counter = 10;

$( document ).ready(function() {

    //WEBSOCKET HANDLERS
    //--------------------------------------------------------

    var simulationWebSocket = new ReconnectingWebSocket("ws://localhost:8080/ws"); //connect to websocket

    simulationWebSocket.onopen = function (event) {
        console.log("Connected to server web socket.");
    };

    simulationWebSocket.onmessage = function (event) {
        var data = JSON.parse(event.data);
        switch(data.name) {
            case undefined:
                console.log("UpdateState: " + event.data);
                var found = false;
                for(var i=0; i<servers.length;i++){
                    if(servers[i].id == data.id){
                        found = true;
                        servers[i].state = data.state;
                        servers[i].term = data.term;
                        servers[i].commitIndex = data.commitIndex;
                        servers[i].lastApplied = data.lastApplied;
                        servers[i].values = data.values;
                        if(servers[i].message != null){
                            servers[i].message.counter--;
                            if(servers[i].message.counter == 0){
                                servers[i].message = null;
                            }
                        }
                        servers[i].counter = server_timeout_counter;
                    } else {
                        servers[i].counter--;
                        if(servers[i].counter == 0){
                            servers[i].state = "TimedOut";
                        }
                    }
                }
                if(found == false){
                    servers.push(new Server(data.id, data.state, data.term, data.commitIndex, data.lastApplied, data.values, null));
                }
                repaint();
                break;
            case "RequestVote":
                console.log("RequestVote: " + event.data);
                var message = new Message(data.name, data.value);
                for(var i=0; i<servers.length;i++){
                    if(servers[i].id == data.value.candidateId){
                        servers[i].message = message;
                    }
                }
                repaint();
                break;
            case "RequestVoteResult":
               console.log("RequestVoteResult: " + event.data);
//                var message = new Message(data.name, data.value);
//                for(var i=0; i<servers.length;i++){
//                    if(servers[i].id == data.value.candidateId){
//                        servers[i].message = message;
//                    }
//                }
//                repaint();
                break;
            case "AppendEntries":
                console.log("AppendEntries: " + event.data);
                var message = new Message(data.name, data.value);
                for(var i=0; i<servers.length;i++){
                    if(servers[i].id == data.value.leaderId){
                        servers[i].message = message;
                    }
                }
                repaint();
                break;
            case "AppendEntriesResult":
                console.log("AppendEntriesResult: " + event.data);
                var message = new Message(data.name, data.value);
                for(var i=0; i<servers.length;i++){
                    if(servers[i].id == data.value.sender){
                        servers[i].message = message;
                    }
                }
                repaint();
                break;
        }
    };

    //SVG HANDLERS
    //--------------------------------------------------------

    var svg = $('svg');

    var ringSpec = {
      cx: 500,
      cy: 400,
      r: 300,
    };

    var serverSpec = function(id) {
      var coord = util.circleCoord((id) / servers.length,
                                   ringSpec.cx, ringSpec.cy, ringSpec.r);
      return {
        cx: coord.x,
        cy: coord.y,
        r: 30,
      };
    };

    $('#ring', svg).attr(ringSpec);

    var SVG = function(tag) {
       return $(document.createElementNS('http://www.w3.org/2000/svg', tag));
    };

    var ARC_WIDTH = 5;

    var repaint = function(message){
        $('#servers', svg).empty();
        $('#server-descriptions').empty();
        servers.forEach(function (server) {
              var s = serverSpec(server.id);
              $('#servers', svg).append(
                SVG('g')
                  .attr('id', 'server-' + server.id)
                  .attr('class', 'server')
                  .append(SVG('text')
                             .attr('class', 'serverid')
                             .text('S' + server.id + ':T' + server.term)
                             .attr(util.circleCoord((server.id) / servers.length,
                                                    ringSpec.cx, ringSpec.cy, ringSpec.r + 50)))
                  .append(SVG('a')
                    .append(SVG('circle')
                               .attr('class', 'background')
                               .attr(s))
                    .append(SVG('g')
                                .attr('class', 'votes'))
                    .append(SVG('path')
                               .attr('style', 'stroke-width: ' + ARC_WIDTH))
                    .append(SVG('text')
                               .attr('class', 'term')
                               .attr({x: s.cx, y: s.cy}))
                    ));
              $('#server-descriptions').append("<div class='col-sm'>" +
              "<h3> Serwer " + server.id + "</h3>" +
              "<table class='table table-bordered'><tr><td>State:</td><td>" + server.state + "</td></tr>" +
              "<tr><td>Term:</td><td>" + server.term + "</td></tr>" +
              "<tr><td>Commit index:</td><td>" + server.commitIndex + "</td></tr>" +
              "<tr><td>Last applied:</td><td>" + server.lastApplied + "</td></tr>" +
              "<tr><td>Values:</td><td>" + JSON.stringify(server.values) + "</td></tr></table>" +
              "</div>");
            });
        renderServers(false);
    }

    var STATE_COLORS = {
      'Leader' : {value: 'Leader', color: '#99ffde'},
      'Follower': {value: 'Follower', color: '#ff966d'},
      'Candidate' : {value: 'Candidate', color: '#8da0cb'},
      'TimedOut': {value: 'TimedOut', color: '#5b585a'},
      'RequestVote': {value: 'RequestVote', color: '#7f402f'},
      'AppendEntries': {value: 'AppendEntries', color: '#da52f2'},
      'Success': {value: 'Success', color: '#a6d854'},
      'Failure': {value: 'Failure', color: '#ed2f2f'}
    };

    var renderServers = function() {
      servers.forEach(function(server) {
        var serverNode = $('#server-' + server.id, svg);
        var stateText = server.state[0];
        $('text.term', serverNode).text(stateText);
        serverNode.attr('class', 'server ' + server.state);
        $('circle.background', serverNode)
          .attr('style', 'fill: ' + STATE_COLORS[server.state].color);
          if(server.message != null && server.state != "TimedOut") {
            switch(server.message.name) {
                case "RequestVote":
                    $('circle.background', serverNode).attr('style', 'fill: ' + STATE_COLORS["RequestVote"].color);
                    $('text.term', serverNode).text("V");
                    $('#vote-text').attr('visibility', 'visible');
                    break;
                case "AppendEntries":
                    $('circle.background', serverNode).attr('style', 'fill: ' + STATE_COLORS["AppendEntries"].color);
                    $('text.term', serverNode).text("A");
                    $('#append-text').attr('visibility', 'visible');
                    break;
                case "AppendEntriesResult":
                    if(server.message.value.success == true){
                        $('circle.background', serverNode).attr('style', 'fill: ' + STATE_COLORS["Success"].color);
                        $('text.term', serverNode).text(":)");
                    } else {
                        $('circle.background', serverNode).attr('style', 'fill: ' + STATE_COLORS["Failure"].color);
                        $('text.term', serverNode).text(":(");
                    }
                    break;
            }
          } else {
            $('#append-text').attr('visibility', 'hidden');
            $('#vote-text').attr('visibility', 'hidden');
            if(server.state == "TimedOut"){
                $('text.term', serverNode).text("110");
            }
          }
      });
    };

    //UTILS
    //--------------------------------------------------------
    var util = {};

    // Really big number. Infinity is problematic because
    // JSON.stringify(Infinity) returns 'null'.
    util.Inf = 1e300;

    util.value = function(v) {
      return function() { return v; };
    };

    // Use with sort for numbers.
    util.numericCompare = function(a, b) {
      return a - b;
    };

    util.circleCoord = function(frac, cx, cy, r) {
      var radians = 2 * Math.PI * (0.75 + frac);
      return {
        x: cx + r * Math.cos(radians),
        y: cy + r * Math.sin(radians),
      };
    };

    util.countTrue = function(bools) {
      var count = 0;
      bools.forEach(function(b) {
        if (b)
          count += 1;
      });
      return count;
    };

    util.makeMap = function(keys, value) {
      var m = {};
      keys.forEach(function(key) {
        m[key] = value;
      });
      return m;
    };

    util.mapValues = function(m) {
      return $.map(m, function(v) { return v; });
    };

    util.clone = function(object) {
      return jQuery.extend(true, {}, object);
    };

    // From http://stackoverflow.com/a/6713782
    util.equals = function(x, y) {
      if ( x === y ) return true;
        // if both x and y are null or undefined and exactly the same

      if ( ! ( x instanceof Object ) || ! ( y instanceof Object ) ) return false;
        // if they are not strictly equal, they both need to be Objects

      if ( x.constructor !== y.constructor ) return false;
        // they must have the exact same prototype chain, the closest we can do is
        // test there constructor.

      var p;
      for ( p in x ) {
        if ( ! x.hasOwnProperty( p ) ) continue;
          // other properties were tested using x.constructor === y.constructor

        if ( ! y.hasOwnProperty( p ) ) return false;
          // allows to compare x[ p ] and y[ p ] when set to undefined

        if ( x[ p ] === y[ p ] ) continue;
          // if they have the same strict value or identity then they are equal

        if ( typeof( x[ p ] ) !== "object" ) return false;
          // Numbers, Strings, Functions, Booleans must be strictly equal

        if ( ! util.equals( x[ p ],  y[ p ] ) ) return false;
          // Objects and Arrays must be tested recursively
      }

      for ( p in y ) {
        if ( y.hasOwnProperty( p ) && ! x.hasOwnProperty( p ) ) return false;
          // allows x[ p ] to be set to undefined
      }
      return true;
    };

    util.greatestLower = function(a, gt) {
      var bs = function(low, high) {
        if (high < low)
          return low - 1;
        var mid = Math.floor((low + high) / 2);
        if (gt(a[mid]))
          return bs(low, mid - 1);
        else
          return bs(mid + 1, high);
      };
      return bs(0, a.length - 1);
    };

    util.clamp = function(value, low, high) {
      if (value < low)
        return low;
      if (value > high)
        return high;
      return value;
    };

});