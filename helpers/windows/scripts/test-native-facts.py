import json
import subprocess
import sys
import winreg

with winreg.OpenKey(winreg.HKEY_CURRENT_USER, r'Software\Microsoft\Windows\CurrentVersion\Lxss') as key:
    expected = {}
    ignored = 0
    for index in range(winreg.QueryInfoKey(key)[0]):
        name = winreg.EnumKey(key, index)
        with winreg.OpenKey(key, name) as child:
            try:
                distribution = winreg.QueryValueEx(child, 'DistributionName')[0]
            except FileNotFoundError:
                ignored += 1
                continue
            base_path = winreg.QueryValueEx(child, 'BasePath')[0]
            version = winreg.QueryValueEx(child, 'Version')[0]
        expected[name.strip('{}').lower()] = (distribution, base_path, version)
result = subprocess.run([sys.argv[1], 'facts'], capture_output=True, check=True, text=True)
facts = json.loads(result.stdout)
actual = {row['guid']: (row['name'], row['basePath'], row['version']) for row in facts['distributions']}
assert actual == expected, {'expected': expected, 'actual': actual}
print(f'native facts: {len(actual)} WSL distributions PASS; ignored {ignored} entries without DistributionName')
