'use strict';

const { Object, SafePromise } = primordials;

const { isModuleNamespaceObject } = require('internal/util/types');
const { isContext } = internalBinding('contextify');
const {
  ERR_INVALID_ARG_TYPE,
  ERR_VM_MODULE_ALREADY_LINKED,
  ERR_VM_MODULE_DIFFERENT_CONTEXT,
  ERR_VM_MODULE_LINKING_ERRORED,
  ERR_VM_MODULE_NOT_MODULE,
  ERR_VM_MODULE_STATUS,
} = require('internal/errors').codes;
const {
  getConstructorOf,
  customInspectSymbol,
  emitExperimentalWarning
} = require('internal/util');
const {
  validateInt32,
  validateUint32,
  validateString,
} = require('internal/validators');

const binding = internalBinding('module_wrap');
const {
  ModuleWrap,
  kUninstantiated,
  kInstantiating,
  kInstantiated,
  kEvaluating,
  kEvaluated,
  kErrored,
} = binding;

const STATUS_MAP = {
  [kUninstantiated]: 'unlinked',
  [kInstantiating]: 'linking',
  [kInstantiated]: 'linked',
  [kEvaluating]: 'evaluating',
  [kEvaluated]: 'evaluated',
  [kErrored]: 'errored',
};

let globalModuleId = 0;
const defaultModuleName = 'vm:module';
const perContextModuleId = new WeakMap();
const wrapToModuleMap = new WeakMap();

const kNoError = Symbol('kNoError');

class SourceTextModule {

  constructor(source, options = {}) {
    this._dependencySpecifiers = undefined;
    this._error = kNoError;
    emitExperimentalWarning('vm.SourceTextModule');

    validateString(source, 'source');
    if (typeof options !== 'object' || options === null)
      throw new ERR_INVALID_ARG_TYPE('options', 'Object', options);

    const {
      context,
      lineOffset = 0,
      columnOffset = 0,
      initializeImportMeta,
      importModuleDynamically,
    } = options;

    if (context !== undefined) {
      if (typeof context !== 'object' || context === null) {
        throw new ERR_INVALID_ARG_TYPE('options.context', 'Object', context);
      }
      if (!isContext(context)) {
        throw new ERR_INVALID_ARG_TYPE('options.context', 'vm.Context',
                                       context);
      }
    }

    validateInt32(lineOffset, 'options.lineOffset');
    validateInt32(columnOffset, 'options.columnOffset');

    if (initializeImportMeta !== undefined &&
        typeof initializeImportMeta !== 'function') {
      throw new ERR_INVALID_ARG_TYPE(
        'options.initializeImportMeta', 'function', initializeImportMeta);
    }

    if (importModuleDynamically !== undefined &&
        typeof importModuleDynamically !== 'function') {
      throw new ERR_INVALID_ARG_TYPE(
        'options.importModuleDynamically', 'function', importModuleDynamically);
    }

    let { identifier } = options;
    if (identifier !== undefined) {
      validateString(identifier, 'options.identifier');
    } else if (context === undefined) {
      identifier = `${defaultModuleName}(${globalModuleId++})`;
    } else if (perContextModuleId.has(context)) {
      const curId = perContextModuleId.get(context);
      identifier = `${defaultModuleName}(${curId})`;
      perContextModuleId.set(context, curId + 1);
    } else {
      identifier = `${defaultModuleName}(0)`;
      perContextModuleId.set(context, 1);
    }

    this._wrap = new ModuleWrap(
      source, identifier, context,
      lineOffset, columnOffset,
    );
    wrapToModuleMap.set(this._wrap, this);
    this._identifier = identifier;
    this._context = context;

    binding.callbackMap.set(this._wrap, {
      initializeImportMeta,
      importModuleDynamically: importModuleDynamically ?
        importModuleDynamicallyWrap(importModuleDynamically) :
        undefined,
    });
  }

  get status() {
    if (!('_error' in this)) {
      throw new ERR_VM_MODULE_NOT_MODULE();
    }
    if (this._error !== kNoError) {
      return 'errored';
    }
    if (this._statusOverride) {
      return this._statusOverride;
    }
    return STATUS_MAP[this._wrap.getStatus()];
  }

  get identifier() {
    if (!('_identifier' in this)) {
      throw new ERR_VM_MODULE_NOT_MODULE();
    }
    return this._identifier;
  }

  get context() {
    if (!('_context' in this)) {
      throw new ERR_VM_MODULE_NOT_MODULE();
    }
    return this._context;
  }

  get namespace() {
    if (!('_wrap' in this)) {
      throw new ERR_VM_MODULE_NOT_MODULE();
    }
    if (this._wrap.getStatus() < kInstantiated) {
      throw new ERR_VM_MODULE_STATUS('must not be unlinked or linking');
    }
    return this._wrap.getNamespace();
  }

  get dependencySpecifiers() {
    if (!('_dependencySpecifiers' in this)) {
      throw new ERR_VM_MODULE_NOT_MODULE();
    }
    if (this._dependencySpecifiers === undefined) {
      this._dependencySpecifiers = this._wrap.getStaticDependencySpecifiers();
    }
    return this._dependencySpecifiers;
  }

  get error() {
    if (!('_error' in this)) {
      throw new ERR_VM_MODULE_NOT_MODULE();
    }
    if (this._error !== kNoError) {
      return this._error;
    }
    if (this._wrap.getStatus() !== kErrored) {
      throw new ERR_VM_MODULE_STATUS('must be errored');
    }
    return this._wrap.getError();
  }

  async link(linker) {
    if (!('_link' in this)) {
      throw new ERR_VM_MODULE_NOT_MODULE();
    }

    if (typeof linker !== 'function') {
      throw new ERR_INVALID_ARG_TYPE('linker', 'function', linker);
    }
    if (this.status !== 'unlinked') {
      throw new ERR_VM_MODULE_ALREADY_LINKED();
    }

    await this._link(linker);

    this._wrap.instantiate();
  }

  async _link(linker) {
    this._statusOverride = 'linking';

    const promises = this._wrap.link(async (identifier) => {
      const module = await linker(identifier, this);
      if (!('_wrap' in module)) {
        throw new ERR_VM_MODULE_NOT_MODULE();
      }
      if (module.context !== this.context) {
        throw new ERR_VM_MODULE_DIFFERENT_CONTEXT();
      }
      if (module.status === 'errored') {
        throw new ERR_VM_MODULE_LINKING_ERRORED();
      }
      if (module.status === 'unlinked') {
        await module._link(linker);
      }
      return module._wrap;
    });

    try {
      if (promises !== undefined) {
        await SafePromise.all(promises);
      }
    } catch (e) {
      this._error = e;
      throw e;
    } finally {
      this._statusOverride = undefined;
    }
  };


  async evaluate(options = {}) {
    if (!('_wrap' in this)) {
      throw new ERR_VM_MODULE_NOT_MODULE();
    }

    if (typeof options !== 'object' || options === null) {
      throw new ERR_INVALID_ARG_TYPE('options', 'Object', options);
    }

    let timeout = options.timeout;
    if (timeout === undefined) {
      timeout = -1;
    } else {
      validateUint32(timeout, 'options.timeout', true);
    }
    const { breakOnSigint = false } = options;
    if (typeof breakOnSigint !== 'boolean') {
      throw new ERR_INVALID_ARG_TYPE('options.breakOnSigint', 'boolean',
                                     breakOnSigint);
    }
    const status = this._wrap.getStatus();
    if (status !== kInstantiated &&
        status !== kEvaluated &&
        status !== kErrored) {
      throw new ERR_VM_MODULE_STATUS(
        'must be one of linked, evaluated, or errored'
      );
    }
    const result = this._wrap.evaluate(timeout, breakOnSigint);
    return { __proto__: null, result };
  }

  static importModuleDynamicallyWrap(importModuleDynamically) {
    // Named declaration for function name
    const importModuleDynamicallyWrapper = async (...args) => {
      const m = await importModuleDynamically(...args);
      if (isModuleNamespaceObject(m)) {
        return m;
      }
      if (!('_wrap' in Object(m))) {
        throw new ERR_VM_MODULE_NOT_MODULE();
      }
      if (m.status === 'errored') {
        throw m.error;
      }
      return m.namespace;
    };
    return importModuleDynamicallyWrapper;
  }

  [customInspectSymbol](depth, options) {
    let ctor = getConstructorOf(this);
    ctor = ctor === null ? SourceTextModule : ctor;

    if (typeof depth === 'number' && depth < 0)
      return options.stylize(`[${ctor.name}]`, 'special');

    const o = Object.create({ constructor: ctor });
    o.status = this.status;
    o.identifier = this.identifier;
    o.context = this.context;
    return require('internal/util/inspect').inspect(o, options);
  }
}

// Declared as static to allow access to #wrap
const importModuleDynamicallyWrap =
  SourceTextModule.importModuleDynamicallyWrap;
delete SourceTextModule.importModuleDynamicallyWrap;

module.exports = {
  SourceTextModule,
  wrapToModuleMap,
  importModuleDynamicallyWrap,
};
