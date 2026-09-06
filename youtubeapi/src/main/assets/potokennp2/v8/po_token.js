/**
 * BotGuard client helpers (strict-mode safe for embedded runtimes like javet).
 */

/**
 * Factory method to create and load a BotGuardClient instance.
 * @param options - Configuration options for the BotGuardClient.
 * @returns A promise that resolves to a loaded BotGuardClient instance.
 */
function loadBotGuard(challengeData) {
  var state = {
    vm: globalThis[challengeData.globalName],
    program: challengeData.program,
    vmFunctions: {},
    syncSnapshotFunction: null
  };

  if (!state.vm)
    throw new Error('[BotGuardClient]: VM not found in the global object');

  if (!state.vm.a)
    throw new Error('[BotGuardClient]: Could not load program');

  var vmFunctionsCallback = function (
    asyncSnapshotFunction,
    shutdownFunction,
    passEventFunction,
    checkCameraFunction
  ) {
    state.vmFunctions = {
      asyncSnapshotFunction: asyncSnapshotFunction,
      shutdownFunction: shutdownFunction,
      passEventFunction: passEventFunction,
      checkCameraFunction: checkCameraFunction
    };
  };

  // The VM delivers the async snapshot function synchronously (during vm.a).
  state.syncSnapshotFunction = state.vm.a(state.program, vmFunctionsCallback, true, undefined, function () {/** no-op */ }, [ [], [] ])[0]

  // NOTE: the caller chain invokes `botguard.snapshot(...)` on the resolved value.
  state.snapshot = snapshot;

  // NOTE: an asynchronous function runs in the VM background and eventually
  // calls `vmFunctionsCallback`; the event loop must be given control before
  // the snapshot is taken (same as the WebView reference flow).
  return new Promise(function (resolve, reject) {
    var i = 0;
    var refreshIntervalId = setInterval(function () {
      if (!!state.vmFunctions.asyncSnapshotFunction) {
        resolve(state);
        clearInterval(refreshIntervalId);
      }
      if (i >= 10000) {
        reject(new Error('asyncSnapshotFunction is null even after 10 seconds'));
        clearInterval(refreshIntervalId);
      }
      i += 1;
    }, 1);
  });
}

/**
 * Takes a snapshot asynchronously.
 * @returns The snapshot result.
 */
function snapshot(args) {
  // NOTE: `this` inside the promise executor is undefined in strict mode,
  // so the vmFunctions object must be captured in the outer scope.
  var vmFunctions = this.vmFunctions;
  return new Promise(function (resolve, reject) {
    if (!vmFunctions.asyncSnapshotFunction)
      return reject(new Error('[BotGuardClient]: Async snapshot function not found'));

    vmFunctions.asyncSnapshotFunction(function (response) { resolve(response) }, [
      args.contentBinding,
      args.signedTimestamp,
      args.webPoSignalOutput,
      args.skipPrivacyBuffer
    ]);
  });
}

function runBotGuard(challengeData) {
  const interpreterJavascript = challengeData.interpreterJavascript.privateDoNotAccessOrElseSafeScriptWrappedValue;

  if (interpreterJavascript) {
    new Function(interpreterJavascript)();
  } else throw new Error('Could not load VM');

  var webPoSignalOutput = [];
  return loadBotGuard({
    globalName: challengeData.globalName,
    globalObj: globalThis,
    program: challengeData.program
  }).then(function (botguard) {
    return botguard.snapshot({ webPoSignalOutput: webPoSignalOutput })
  }).then(function (botguardResponse) {
    if (!webPoSignalOutput.length) {
      // Decode the response to reveal the VM's recorded errors (if any).
      var respHead = '';
      try {
        var r = String(botguardResponse).replace(/-/g, '+').replace(/_/g, '/');
        respHead = atob(r).replace(/[^ -~]/g, '.');
      } catch (e) {
        respHead = 'raw-head=' + String(botguardResponse).slice(0, 120) + '; atob-err=' + e;
      }
      throw new Error('webPoSignalOutput is empty; respLen=' +
        (botguardResponse ? String(botguardResponse).length : 'null') +
        '; resp=' + respHead);
    }
    return { webPoSignalOutput: webPoSignalOutput, botguardResponse: botguardResponse };
  })
}

function obtainPoToken(webPoSignalOutput, integrityToken, identifier) {
  const getMinter = webPoSignalOutput[0];

  if (!getMinter)
    throw new Error('PMD:Undefined');

  const mintCallback = getMinter(integrityToken);

  if (!(mintCallback instanceof Function))
    throw new Error('APF:Failed');

  const result = mintCallback(identifier);

  if (!result)
    throw new Error('YNJ:Undefined');

  if (!(result instanceof Uint8Array))
    throw new Error('ODM:Invalid');

  return result;
}