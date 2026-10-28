"""One ordered build/check run. The complete log is committed to disk after the process finishes."""
import subprocess,pathlib,os,urllib.parse,sys,time,json
root=pathlib.Path(__file__).resolve().parents[1];os.chdir(root)
env=os.environ.copy();p=urllib.parse.urlparse(env.get('HTTPS_PROXY',''))
if p.hostname:env['GRADLE_OPTS']=f'-Dhttp.proxyHost={p.hostname} -Dhttp.proxyPort={p.port} -Dhttps.proxyHost={p.hostname} -Dhttps.proxyPort={p.port}'
runner=['bash','../tools/gradle-8.9/bin/gradle'] if (root.parent/'tools/gradle-8.9').exists() else ['bash','./gradlew']
cmd=runner+['--no-daemon','--max-workers=2']+(sys.argv[1:] or ['clean','lintDebug','testDebugUnitTest','assembleDebug','assembleDebugAndroidTest'])
print('Starting ordered verification',flush=True);start=time.time();r=subprocess.run(cmd,env=env,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
(root/'evidence/final-build.log').write_bytes(r.stdout);(root/'evidence/build-result.json').write_text(json.dumps({'exit_code':r.returncode,'seconds':round(time.time()-start,2),'command':cmd},indent=2));print(r.stdout.decode(errors='replace')[-7000:],flush=True);sys.exit(r.returncode)
