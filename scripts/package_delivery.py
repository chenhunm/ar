"""Package only an actual successful build and its unedited evidence.

Write/update 验收报告.md from observed device results before running this script.
This script does not manufacture or interpret Android test pass results.
"""
import hashlib,json,pathlib,re,shutil,subprocess,xml.etree.ElementTree as ET,zipfile
root=pathlib.Path(__file__).resolve().parents[1]
out=root.parent/'deliverables';out.mkdir(exist_ok=True)
ev=root/'evidence'
result=json.loads((ev/'build-result.json').read_text())
assert result['exit_code']==0,'Build did not pass'
apk=root/'app/build/outputs/apk/debug/app-debug.apk'
metadata=json.loads((apk.parent/'output-metadata.json').read_text())
version=metadata['elements'][0]['versionName']
name=f'HuanBanAR-{version}-debug.apk'
sdk=pathlib.Path(re.search(r'^sdk.dir=(.+)$',(root/'local.properties').read_text(),re.M).group(1))
buildtools=next(p for p in sorted((sdk/'build-tools').iterdir(),reverse=True) if (p/'apksigner').exists())
for label,command in [('signature',['bash',str(buildtools/'apksigner'),'verify','--verbose','--print-certs',str(apk)]),('apk-metadata',[str(buildtools/'aapt'),'dump','badging',str(apk)])]:
    completed=subprocess.run(command,capture_output=True)
    (ev/f'{label}.txt').write_bytes(completed.stdout+completed.stderr)
    assert completed.returncode==0,label
for source,destination in [(root/'app/build/reports/tests/testDebugUnitTest',ev/'unit-test-report'),(root/'app/build/test-results/testDebugUnitTest',ev/'unit-test-results')]:
    if destination.exists():shutil.rmtree(destination)
    shutil.copytree(source,destination)
for report in (root/'app/build/reports').glob('lint-results-debug.*'):shutil.copy2(report,ev/report.name)
suites=[ET.parse(p).getroot() for p in (ev/'unit-test-results').glob('TEST-*.xml')]
assert suites and all(int(s.get('failures','0'))+int(s.get('errors','0'))==0 for s in suites)
unit_count=sum(int(s.get('tests','0')) for s in suites)
sha=hashlib.sha256(apk.read_bytes()).hexdigest()
(root/'SHA256SUMS.txt').write_text(f'{sha}  {name}\n')
shutil.copy2(apk,out/name)
shutil.copy2(root/'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk',ev/'app-debug-androidTest.apk')
report=root/'验收报告.md'
assert report.exists(),'A factual validation report is required'
shutil.copy2(report,out/'HuanBanAR-validation.md')
archive=out/'HuanBanAR-source-and-tests.zip'
with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED,compresslevel=6) as z:
    for p in sorted(root.rglob('*')):
        if not p.is_file():continue
        relative=p.relative_to(root)
        if any(part in ('.gradle','build','.git','__pycache__','.native-deps','.cxx','signing') for part in relative.parts) or relative.name=='local.properties' or p.suffix.lower() in ('.jks','.keystore'):continue
        z.write(p,'HuanBanAR/'+str(relative))
with zipfile.ZipFile(archive) as z:assert z.testzip() is None
print(json.dumps({'apk':str(out/name),'bytes':apk.stat().st_size,'version':version,'sha256':sha,'unit_tests':unit_count,'source':str(archive),'source_bytes':archive.stat().st_size,'report':str(out/'HuanBanAR-validation.md')},ensure_ascii=False))
