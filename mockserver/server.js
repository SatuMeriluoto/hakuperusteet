var express = require('express')
var requestify = require('requestify')
var crypto = require('crypto')
var bodyParser = require('body-parser')
var xml = require('xml');

process.env.NODE_TLS_REJECT_UNAUTHORIZED = "0";

var ldap = require('ldapjs');
var server = ldap.createServer();
server.bind('ou=People, dc=opintopolku, dc=fi', function(req, res, next) {
  console.log('bind DN: ' + req.dn.toString());
  console.log('bind PW: ' + req.credentials);
  // AUTH CHECK WOULD BE HERE BUT PASSING ALL
  res.end();
  return next();
});

server.search('ou=People, dc=opintopolku, dc=fi', function(req, res, next) {
  var obj = {
    dn: 'uid=testitest,ou=People,dc=opintopolku,dc=fi',
    attributes: {
      employeeNumber: "1.2.246.562.24.00000001337",
      uid: "testitest",
      sn: "Testaaja",
      givenname: "Testi",
      description: '["APP_HAKUPERUSTEETADMIN_CRUD"]'
    }
  };
  res.send(obj);
  res.end();
});

server.listen(process.env.LDAP_PORT || 1389, function() {
  console.log('ldapjs listening at ' + server.url);
});

var app = express();
// Body parser
app.use(bodyParser.json())
app.use(bodyParser.urlencoded({ extended: false }))

// Default Content-Type to Application/Json
app.use(function(req, res, next) { res.setHeader("Content-Type", "application/json"); return next(); });

// Koodisto-Service
app.use(express.static(__dirname + '/static'));

// Ryhmasahkoposti-Service
app.post('/ryhmasahkoposti-service/email', function(req, res){
  console.log("Sending email: " + JSON.stringify(req.body))
  res.send({});
});

// Authentication-Service
app.post('/authentication-service/resources/s2s/hakuperusteet', function(req, res){
  if (req.body.firstName == "Error409") {
    res.sendStatus(409)
  } else if (req.body.firstName == "Error500") {
    res.sendStatus(500)
  } else {
    res.send({ "personOid": "1.2.246.562.24.11523238937" });
  }
});

// Oppijan-tunnistus
var oppijanTunnistusEmails = {}
app.post('/oppijan-tunnistus/api/v1/token', function(req, res){
  var sha256 = crypto.createHash('sha256');
  sha256.update(req.body.email + Date.now())
  var token = sha256.digest('hex');
  console.log("Sending verification email: " + JSON.stringify(req.body) + " with token " + token)
  var callback_url = req.body.url + token;
  console.log("Callback URL is ");
  console.log(callback_url);
  oppijanTunnistusEmails[token] = req.body.email;
  res.send(callback_url);
});
app.get('/oppijan-tunnistus/api/v1/token/:token', function(req, res){
  var token = req.params.token
  console.log("Verifying token " + token)
  if (token == "mochaTestToken") {
    res.send({ "valid" : true, "email" : "mochatest@example.com"});
  } else {
    var email = oppijanTunnistusEmails[token]
    if(email) {
      res.send({ "valid" : true, "email" : email});
    } else {
      res.send({ "valid" : false });
    }
  }
});

// Vetuma
app.post('/VETUMAPayment', function(req, res){
  var p = req.body
  var SO = ""
  var PAYID = "441265046723995"
  var PAID = "15092588INWX0000"
  var STATUS = "SUCCESSFUL"
  var SHARED_SECRET = "TESTIASIAKAS11-873C992B8C4C01EC8355500CAA709B37EA43BC2E591ABF29FEE5EAFE4DCBFA35"
  var op = [p["RCVID"], p["TIMESTMP"], SO, p["LG"], p["RETURL"], p["CANURL"], p["ERRURL"], PAYID, p["REF"], p["ORDNR"], PAID, STATUS]
  var sha256 = crypto.createHash('sha256');
  sha256.update(op.join('&') + "&" + SHARED_SECRET + "&")
  var mac = sha256.digest('hex').toUpperCase()
  res.writeHead(200, {'Content-Type': 'text/html'});
  res.write("<form method=POST name=\"form\" action=\"" + p["RETURL"] +"\"><input type=\"submit\" value=\"Palaa myyj&auml;n palveluun\">");
  res.write("<input type=\"hidden\" name=\"RCVID\" value=\"" + p["RCVID"]+ "\" />");
  res.write("<input type=\"hidden\" name=\"TIMESTMP\" value=\"" + p["TIMESTMP"]+ "\" />");
  res.write("<input type=\"hidden\" name=\"SO\" value=\"" + SO + "\" />");
  res.write("<input type=\"hidden\" name=\"LG\" value=\"" + p["LG"]+ "\" />");
  res.write("<input type=\"hidden\" name=\"RETURL\" value=\"" + p["RETURL"]+ "\" />");
  res.write("<input type=\"hidden\" name=\"CANURL\" value=\"" + p["CANURL"]+ "\" />");
  res.write("<input type=\"hidden\" name=\"ERRURL\" value=\"" + p["ERRURL"]+ "\" />");
  res.write("<input type=\"hidden\" name=\"MAC\" value=\"" + mac + "\" />");
  res.write("<input type=\"hidden\" name=\"PAYID\" value=\"" + PAYID + "\" />");
  res.write("<input type=\"hidden\" name=\"REF\" value=\"" + p["REF"]+ "\" />");
  res.write("<input type=\"hidden\" name=\"ORDNR\" value=\"" + p["ORDNR"]+ "\" />");
  res.write("<input type=\"hidden\" name=\"PAID\" value=\"" + PAID+ "\" />");
  res.write("<input type=\"hidden\" name=\"STATUS\" value=\"" + STATUS + "\" />");
  res.write("<input type=\"hidden\" name=\"TRID\" value=\"" + "TODO"+ "\" />");
  res.write("</form>");
  res.write("<script>document.forms[\"form\"].submit();</script>");
  res.end();
});

// Application form (Aalto, UAF)
app.post('/formredirect', function(req, res) {
  res.writeHead(200, {'Content-Type': 'text/html'})
  res.write("<html>")
  res.write("<body>")
  res.write("<script>document.domain = location.hostname</script>")
  res.write("<div class='mockRedirect'>Mock redirect form got values:</div>")
  res.write(JSON.stringify(req.body))
  res.write("</body>")
  res.write("</html>")
  res.end()
})

// CAS
app.get('/cas/serviceValidate', function(req, res){
  var msg = {"cas:serviceResponse": [ { "_attr": { "xmlns:cas": "http://www.yale.edu/tp/cas"} }, { "cas:authenticationSuccess": [{ "cas:user": "testitest"} ]}]}
  console.log(msg)
  res.set('Content-Type', 'text/xml');
  res.send(xml(msg));
});
app.get('/cas/login', function(req, res){
  var service = decodeURI(req.query['service'])
  res.redirect(service + "?ticket=ST_MOCK");
});
app.post('/cas/v1/tickets', function(req, res){
  res.append('Location', 'http://localhost:' + appPort + '/cas/v1/tickets/TGT-123');
  res.status(201)
  res.send({});
});
app.post('/cas/v1/tickets/TGT-123', function(req, res){
  res.send("ST-123");
});
app.get('/authentication-service/j_spring_cas_security_check', function(req, res){
  res.append('Set-Cookie', 'JSESSIONID=foobar-123');
  res.send({});
});
app.get('/ryhmasahkoposti-service/j_spring_cas_security_check', function(req, res){
  res.append('Set-Cookie', 'JSESSIONID=foobar-123');
  res.send({});
});
var appPort = process.env.PORT || 3000
console.log("Mock server listening " + appPort)
app.listen(appPort);
