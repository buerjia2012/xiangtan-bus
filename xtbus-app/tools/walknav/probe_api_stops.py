import json, hashlib, base64, urllib.parse, urllib.request, ssl
from Crypto.Cipher import AES

ctx = ssl.create_default_context(); ctx.check_hostname = False; ctx.verify_mode = ssl.CERT_NONE
UA = 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36'
KEY = b'422556651C7F7B2B5C266EED06068230'

def get(url):
    req = urllib.request.Request(url, headers={'User-Agent': UA, 'Referer': 'https://web.chelaile.net.cn/customer_ch5/'})
    return urllib.request.urlopen(req, timeout=25, context=ctx).read().decode('utf-8', 'replace')

def sign(obj):
    s = json.dumps(obj, separators=(',', ':'), ensure_ascii=False)[1:-1].replace(':', '=').replace(',', '&')
    return hashlib.md5((s + 'qwihrnbtmj').encode()).hexdigest()

def decrypt(enc):
    raw = AES.new(KEY, AES.MODE_ECB).decrypt(base64.b64decode(enc))
    return raw[:-raw[-1]].decode('utf-8', 'replace')

common = {'s': 'h5', 'v': '9.1.2', 'vc': '1', 'src': 'app_xiangtanchuxing', 'userId': 'demo', 'sign': '1',
          'cityId': '173', 'lat': '27.823894', 'lng': '112.938037', 'gpstype': 'gcj'}

def action(handler, data=None):
    payload = dict(data or {}); payload['cryptoSign'] = sign(payload)
    p = dict(common); p.update(payload)
    body = get('https://web.chelaile.net.cn/api/' + handler + '?' + urllib.parse.urlencode(p))
    d = json.loads(body.split('**YGKJ', 1)[1].rsplit('YGKJ##', 1)[0])
    jr = d.get('jsonr', d)
    data = jr.get('data') or {}
    if isinstance(data, dict) and data.get('encryptResult'):
        return json.loads(decrypt(data['encryptResult']))
    return data

# 1) 搜索线路，拿 lineId
try:
    r = action('basesearch/client/clientSearch.action', {'keyword': '1路', 'cityId': '173'})
    print('search keys:', list(r.keys())[:12])
    lines = r.get('lines') or r.get('lineInfos') or []
    print('lines:', len(lines))
    for l in lines[:6]:
        print('  ', json.dumps(l, ensure_ascii=False)[:260])
except Exception as e:
    print('ERR search', repr(e)[:200])

# 2) 已知 lineId 的线路走向
for lid in ['731154965727']:
    try:
        r = action('bus/line!lineRoute.action', {'lineId': lid})
        print('route keys:', list(r.keys())[:15])
        sts = r.get('stations') or r.get('stops') or []
        print('stations:', len(sts))
        for s in sts[:8]:
            print('  ', s.get('name') or s.get('sn'), s.get('lat'), s.get('lng'), s.get('sId'))
    except Exception as e:
        print('ERR route', repr(e)[:200])
