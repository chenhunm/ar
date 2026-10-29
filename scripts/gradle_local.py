"""Use the runtime current proxy for local builds; never hardcode ephemeral ports."""
import os,urllib.parse,subprocess,sys,pathlib
os.chdir(pathlib.Path(__file__).resolve().parents[1])
p=urllib.parse.urlparse(os.environ.get('HTTPS_PROXY',''))
env=os.environ.copy()
if p.hostname:env['GRADLE_OPTS']=f'-Dhttp.proxyHost={p.hostname} -Dhttp.proxyPort={p.port} -Dhttps.proxyHost={p.hostname} -Dhttps.proxyPort={p.port}'
sys.exit(subprocess.call(['bash','../tools/gradle-8.9/bin/gradle','--no-daemon','--max-workers=2',*sys.argv[1:]],env=env))
