use log::debug;
use log::error;
use rquickjs::{object::ObjectKeysIter, Context, Ctx, IntoAtom, Object, Persistent, object::Accessor, Value, function::ParamRequirement};
use wasm_macros::wasm_export;

use crate::js_to_java_proxy::JSJavaProxy;
use crate::context::get_context_pointer;
use rquickjs::function::IntoJsFunc;
use rquickjs::IntoJs;

#[wasm_export]
pub fn object_create(ctx: &Ctx<'_>) -> rquickjs::Result<Option<Box<Persistent<Object<'static>>>>> {
    let js_object = rquickjs::Object::new(ctx.clone())?;
    let persistent = Persistent::save(ctx, js_object);
    let result = Box::new(persistent);
    Ok(Some(result))
}

#[wasm_export]
pub fn object_close(_context: &Context, object: Box<Persistent<Object<'static>>>) -> bool {
    drop(object);
    true
}

#[wasm_export]
pub fn object_size(
    ctx: &Ctx<'_>,
    persistent_object: &Persistent<Object<'static>>,
) -> rquickjs::Result<i32> {
    let v = persistent_object.clone().restore(ctx)?;
    Ok(v.len() as i32)
}

#[wasm_export]
pub fn object_contains_key(
    ctx: &Ctx<'_>,
    persistent_object: &Persistent<Object<'static>>,
    key: JSJavaProxy,
) -> rquickjs::Result<bool> {
    let v = persistent_object.clone().restore(ctx)?;
    v.contains_key(key)
}

#[wasm_export]
pub fn object_get_value(
    ctx: &Ctx<'_>,
    persistent_object: &Persistent<Object<'static>>,
    key: JSJavaProxy,
) -> rquickjs::Result<JSJavaProxy> {
    let object = persistent_object.clone().restore(ctx)?;
    let key = key.into_atom(ctx)?;
    if object.contains_key(key.clone())? {
        debug!("Key {:?} exists in object", key.to_string()?);
        object.get(key.clone())?
    } else {
        debug!("Key {:?} does not exist in object", key.to_string()?);
        Ok(JSJavaProxy::Null)
    }
}

#[wasm_export]
pub fn object_remove_value(
    ctx: &Ctx<'_>,
    persistent_object: &Persistent<Object<'static>>,
    key: JSJavaProxy,
) -> rquickjs::Result<bool> {
    let v = persistent_object.clone().restore(ctx)?;
    v.remove(key)?;
    Ok(true)
}

#[wasm_export]
pub fn object_set_value(
    ctx: &Ctx<'_>,
    persistent_object: &Persistent<Object<'static>>,
    key: JSJavaProxy,
    value: JSJavaProxy,
) -> rquickjs::Result<bool> {
    let v = persistent_object.clone().restore(ctx)?;
    v.set(key, value)?;
    Ok(true)
}

#[wasm_export]
pub fn object_key_set(
    ctx: &Ctx<'_>,
    persistent_object: &Persistent<Object<'static>>,
) -> rquickjs::Result<JSJavaProxy> {
    let v = persistent_object.clone().restore(ctx)?;

    let object_keys: ObjectKeysIter<'_, JSJavaProxy> = v.keys();

    let mut keys = Vec::new();
    for key in object_keys.into_iter() {
        keys.push(key?);
    }
    debug!("Keys: {:?}", keys);
    Ok(JSJavaProxy::Array(keys))
}

// Everything below this line is a disaster.
//
// What I want to do:
//  * Ideally, get the reference to JavaFunction from an existing Function so I don't
//    have to pass in "getptr: i32", I can "getter: &Persistent<Function>"
//  * However I get it, use JavaFunction but just prepend two arguments to the call,
//    the object and the property: value = getter(object, key); setter(object, key, value)
//  * make the call into "v.prop" not require 100 characters, most of which are punctuation.
//
// What I ended up doing because I don't know Rust
//  * Cloning the *entire* JSFunction class into a JSFunctionGetterSetter which pushes
//    the two additional arguments at the start
//  * Weep at the lost hours just getting this far
// 

#[wasm_export]
pub fn object_define_property_get_set(
    ctx: &Ctx<'_>,
    persistent_object: &Persistent<Object<'static>>,
    key: JSJavaProxy,
    getptr: i32,
    setptr: i32,
    flags: i32,
) -> rquickjs::Result<bool> {
    let v = persistent_object.clone().restore(ctx)?;
    let context_ptr = get_context_pointer(ctx) as i32;
    let dkey = key.into_js(ctx).unwrap();
    if getptr == 0 {
        error!("Getter function index cannot be 0");
        Err(rquickjs::Error::Exception)
    } else {
        const ENUMERABLE_FLAG: i32 = 1;
        const CONFIGURABLE_FLAG: i32 = 2;
        let getfunc = JavaFunctionGetterSetter::new(v.clone().into(), dkey.clone(), context_ptr, getptr);
        if setptr != 0 {
            let setfunc = JavaFunctionGetterSetter::new(v.clone().into(), dkey.clone(), context_ptr, setptr);
            let mut accessor = Accessor::new(getfunc, setfunc);
            if (flags & ENUMERABLE_FLAG) != 0 {
                accessor = accessor.enumerable();
            }
            if (flags & CONFIGURABLE_FLAG) != 0 {
                accessor = accessor.configurable();
            }
            v.prop::<JSJavaProxy, Accessor<JavaFunctionGetterSetter, JavaFunctionGetterSetter>, ((), ())>(JSJavaProxy::convert(dkey).unwrap(), accessor)?;
        } else {
            let mut accessor = Accessor::new_get(getfunc);
            if (flags & ENUMERABLE_FLAG) != 0 { 
                accessor = accessor.enumerable();
            }
            if (flags & CONFIGURABLE_FLAG) != 0 {
                accessor = accessor.configurable();
            }
            v.prop::<JSJavaProxy, Accessor<JavaFunctionGetterSetter, ()>, (JSJavaProxy, (), ())>(JSJavaProxy::convert(dkey).unwrap(), accessor)?;
        }
        Ok(true)
    }
}


pub struct JavaFunctionGetterSetter<'js> {
    owner: Value<'js>,
    key: Value<'js>,
    call: Box<dyn Fn(JSJavaProxy) -> JSJavaProxy>,
}

impl<'js> JavaFunctionGetterSetter<'js> {
    pub fn new(owner: Value<'js>, key: Value<'js>, context: i32, func: i32) -> Self {
        debug!("Creating Java function: {} on context {}", func, context);
        let call = move |arg: JSJavaProxy| {
            debug!(
                "Calling Java function: {} on context {} with arg: {:?}",
                func, context, arg
            );

            // Serialize args
            let args = rmp_serde::to_vec(&arg).expect("MsgPack encode failed");
            let args_len = args.len();
            let args_ptr = args.as_ptr();
            std::mem::forget(args); // Prevent drop

            // Call Java function
            let result = unsafe { crate::quickjs_function::call_java_function(context, func, args_ptr, args_len) } as u64;

            // Deserialize result, result is a packed pointer and length
            let result_ptr = (result >> 32) as usize;
            let result_len = (result & 0xFFFFFFFF) as usize;
            let result_bytes =
                unsafe { std::slice::from_raw_parts(result_ptr as *const u8, result_len) };
            let result: JSJavaProxy = match rmp_serde::from_slice(result_bytes) {
                Ok(result) => result,
                Err(e) => {
                    error!(
                        "MsgPack decode of return value (type JSJavaProxy) from java context failed: {}",
                        e
                    );
                    JSJavaProxy::Undefined
                }
            };
            unsafe { crate::dealloc(result_ptr as *mut u8, result_len) };

            debug!(
                "Calling Java function: {} on context {} with arg: {:?} -> {:?}",
                func, context, arg, result
            );

            result
        };
        Self {
            owner,
            key,
            call: Box::new(call),
        }
    }
}

impl<'js, P> IntoJsFunc<'js, P> for JavaFunctionGetterSetter<'js> {
    fn param_requirements() -> rquickjs::function::ParamRequirement {
        // We cannot give any hint on the number of expected parameters
        ParamRequirement::any()
    }

    fn call<'a>(
        &self,
        params: rquickjs::function::Params<'a, 'js>,
    ) -> rquickjs::Result<Value<'js>> {
        let mut args: Vec<JSJavaProxy> = Vec::new();
        args.push(JSJavaProxy::convert(self.owner.clone())?);
        args.push(JSJavaProxy::convert(self.key.clone())?);
        for i in 0..params.len() {
            let value = params.arg(i);
            if let Some(v) = value {
                args.push(JSJavaProxy::convert(v)?);
            }
        }

        let arg = JSJavaProxy::Array(args);
        let result = (self.call)(arg);

        // If the result is an exception, throw it
        if let JSJavaProxy::Exception(message, _stacktrace) = &result {
            let exception = rquickjs::Exception::from_message(params.ctx().clone(), message)?;
            Err(params.ctx().throw(exception.into_value()))
        } else {
            result.into_js(params.ctx())
        }
    }
}
