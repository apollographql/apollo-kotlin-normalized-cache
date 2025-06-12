// This is a modified version of @cashapp/sqldelight-sqljs-worker that loads/saves the db file via OPFS.
// Original:  https://github.com/sqldelight/sqldelight/blob/master/drivers/web-worker-driver/sqljs/sqljs.worker.js

import initSqlJs from "sql.js";

let db = null;
let syncAccessHandle = null;

async function createDatabase() {
    let SQL = await initSqlJs({locateFile: file => '/sql-wasm.wasm'});

    // Loads the database from OPFS if it exists, otherwise creates a new one.
    const opfsRoot = await navigator.storage.getDirectory();
    const fileHandle = await opfsRoot.getFileHandle("apollo.db", {
        create: true,
    });
    syncAccessHandle = await fileHandle.createSyncAccessHandle();
    const fileContents = new Uint8Array(syncAccessHandle.getSize());
    syncAccessHandle.read(fileContents);
    db = new SQL.Database(fileContents);
}

function onModuleReady() {
    const data = this.data;

    switch (data && data.action) {
        case "exec":
            if (!data["sql"]) {
                throw new Error("exec: Missing query string");
            }

            return postMessage({
                id: data.id,
                results: db.exec(data.sql, data.params)[0] ?? {values: []}
            });
        case "begin_transaction":
            return postMessage({
                id: data.id,
                results: db.exec("BEGIN TRANSACTION;")
            })
        case "end_transaction":
            let results = db.exec("END TRANSACTION;");

            // Save the database to OPFS after a successful transaction.
            const databaseContents = db.export();
            syncAccessHandle.truncate(0);
            syncAccessHandle.write(databaseContents, {position: 0});
            syncAccessHandle.flush();

            return postMessage({
                id: data.id,
                results: results
            })
        case "rollback_transaction":
            return postMessage({
                id: data.id,
                results: db.exec("ROLLBACK TRANSACTION;")
            })
        default:
            throw new Error(`Unsupported action: ${data && data.action}`);
    }
}

function onError(err) {
    return postMessage({
        id: this.data.id,
        error: err
    });
}

if (typeof importScripts === "function") {
    db = null;
    const sqlModuleReady = createDatabase()
    self.onmessage = (event) => {
        return sqlModuleReady
            .then(onModuleReady.bind(event))
            .catch(onError.bind(event));
    }
}
