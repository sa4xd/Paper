const express = require('express');
const axios = require('axios');
const sharp = require('sharp');
const etag = require('etag');
const crypto = require('crypto');
const cacheManager = require('cache-manager');
const fsStore = require('cache-manager-fs-hash');

const app = express();
const MAX_DIM = 2000;

// 初始化磁盘缓存（最大 500MB）
const diskCache = cacheManager.caching({
  store: fsStore,
  options: {
    path: 'diskcache',
    ttl: 60 * 60 * 24 * 365, // 1年
    maxsize: 500 * 1024 * 1024, // 500MB
    subdirs: true,
    zip: true
  }
});

app.use(express.json());

// CORS 中间件
app.use((req, res, next) => {
  res.set({
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Methods': 'GET, OPTIONS',
    'Access-Control-Allow-Headers': 'Content-Type'
  });
  if (req.method === 'OPTIONS') {
    return res.status(204).send();
  }
  next();
});







// 根路由
app.get('/', async (req, res) => {
  let lastModified = new Date().toUTCString();
  const { url, w, h, output = 'jpg' } = req.query;
  const width = parseInt(w);
  const height = parseInt(h);
  const quality = Math.min(Math.max(parseInt(req.query.quality) || 94, 1), 100);

  if (!url) {
    res.set({
      'Cache-Control': 'public, max-age=3600',
      'Access-Control-Allow-Origin': '*'
    });
    return res.send('Hello, 世界！这是一个简单的 Express 图片服务。');
  }

  // 构建缓存键
  const paramsString = `${url}-${w || ''}-${h || ''}-${quality}-${output}`;
  const cacheKey = crypto.createHash('md5').update(paramsString).digest('hex');
  const generatedEtag = etag(cacheKey);

  // 判断缓存命中
  if (req.headers['if-none-match'] === generatedEtag) {
    res.set({
      'ETag': generatedEtag,
      'Last-Modified': req.headers['if-modified-since'] || lastModified,
      'Access-Control-Allow-Origin': '*'
    });
    return res.status(304).send();
  }

  // 尝试读取缓存
  const cachedBuffer = await diskCache.get(cacheKey);
  if (cachedBuffer) {
    res.set({
      'Content-Type': 'image/jpeg',
      'ETag': generatedEtag,
      'Last-Modified': lastModified,
      'Cache-Control': 'public, max-age=31536000',
      'Access-Control-Allow-Origin': '*'
    });
    return res.end(cachedBuffer);
  }

  // 获取 Last-Modified
  try {
    const head = await axios.head(url, { timeout: 10000 });
    if (head.headers['last-modified']) {
      lastModified = head.headers['last-modified'];
    }
  } catch (e) {
    // 使用默认时间
  }

  // 获取图片流
  let imageStream;
  try {
    const response = await axios.get(url, { responseType: 'stream', timeout: 10000 });
    imageStream = response.data;
  } catch (e) {
    return res.status(400).send('Failed to fetch image');
  }

  // 限制最大尺寸
  const targetWidth = (width > 0 && width <= MAX_DIM) ? width : null;
  const targetHeight = (height > 0 && height <= MAX_DIM) ? height : null;

  // 构建 sharp 转换流
  let transformer = sharp().rotate();
  if (targetWidth && targetHeight) {
    transformer = transformer.resize(targetWidth, targetHeight, { fit: 'cover' });
  } else if (targetWidth || targetHeight) {
    transformer = transformer.resize(targetWidth, targetHeight, { fit: 'inside' });
  }

  transformer = transformer.toFormat(output === 'png' ? 'png' : 'jpeg', { quality });

  // 收集输出并缓存
  const chunks = [];
  transformer.on('data', chunk => chunks.push(chunk));
  transformer.on('end', async () => {
    const buffer = Buffer.concat(chunks);
    await diskCache.set(cacheKey, buffer);
    res.set({
      'Content-Type': output === 'png' ? 'image/png' : 'image/jpeg',
      'ETag': generatedEtag,
      'Last-Modified': lastModified,
      'Cache-Control': 'public, max-age=31536000',
      'Access-Control-Allow-Origin': '*'
    });
    res.end(buffer);
  });

  imageStream.pipe(transformer);
});









const PORT = process.env.SERVER_PORT || process.env.PORT || 3000;        // http服务订阅端口

app.listen(PORT, () => console.log(`http server is running on port:${PORT}!`));
