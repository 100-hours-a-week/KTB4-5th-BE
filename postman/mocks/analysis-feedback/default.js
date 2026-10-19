// AiAnalysisClient의 경로·응답 DTO에 근거한 로컬 검증용 대역이다.
const http = require('http');
http.createServer((req, res) => {
  let status = 501;
  let body = {};
  if (req.method === 'GET' && req.url === '/health/ready') status = 200;
  const match = req.url.match(/^\/ai\/v1\/analyses\/(success|unavailable|busy|rejected)\/feedback$/);
  if (req.method === 'POST' && match) {
    status = {success: 201, unavailable: 503, busy: 429, rejected: 404}[match[1]];
    body = {feedbackId: 'feedback-1', status: 'RECORDED'};
    if (req.headers['x-internal-api-key'] !== 'test-key' || req.body?.itemId !== 'item-1'
        || req.body?.confirmed?.name !== '우유' || req.body?.confirmed?.quantity !== 2) status = 400;
  }
  res.writeHead(status, {'Content-Type': 'application/json'});
  res.end(JSON.stringify(body));
}).listen(process.env.PORT);
