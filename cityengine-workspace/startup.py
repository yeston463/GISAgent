# CityEngine workspace automation entrypoint.
# CityEngine executes this file on startup. The production CGA project should
# consume queued JSON files from automation/jobs and write export manifests to
# automation/results after SLPK/OBJ generation completes.

if __name__ == '__startup__':
    print('GIS Agent CityEngine automation workspace ready')
