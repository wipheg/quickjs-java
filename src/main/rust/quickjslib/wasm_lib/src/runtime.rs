use log::debug;
use rquickjs::{Ctx, Module, Runtime};
use rquickjs::loader::{Loader, Resolver};
use rquickjs::module::Declared;
use wasm_macros::wasm_export;
use rquickjs::runtime::UserDataGuard;
use crate::context::ContextPtr;
use crate::js_to_java_proxy::JSJavaProxy;

#[wasm_export]
pub fn create_runtime() -> Box<Runtime> {
    debug!("Created new QuickJS runtime");

    let runtime = Runtime::new().unwrap();
    let interrrupt_handler = move || {
        let result = unsafe { js_interrupt_handler() };
        // False lets continue the flow, true stops the execution
        result == 1
    };

    runtime.set_interrupt_handler(Some(Box::new(interrrupt_handler)));
    runtime.set_host_promise_rejection_tracker(Some(Box::new(|ctx, promise, reason, is_handled| {
        debug!("Calling promise rejection with reason: {:?}", reason);
        let exception = reason.as_exception().unwrap();
        let message = exception.message().unwrap();
        let stack = exception.stack().unwrap();
        let serialized = rmp_serde::to_vec(&JSJavaProxy::Exception(message, stack)).expect("MsgPack encode failed");
        let ctx_pointer: UserDataGuard<ContextPtr> = ctx.userdata().unwrap();
        unsafe {
            handle_rejected_promise(
                ctx_pointer.ptr,
                promise.as_promise().unwrap() as *const _ as u64,
                //&reason_proxy as *const _ as u64,
                serialized.as_ptr() as u32,
                serialized.len() as u32,
                is_handled as u32
            );
        }
        std::mem::forget(serialized); // Prevent drop
    })));

    Box::new(runtime)
}

#[link(wasm_import_module = "env")]
extern "C" {
    pub fn js_interrupt_handler() -> i32;
}

#[wasm_export]
pub fn close_runtime(runtime: Box<Runtime>) {
    debug!("Closing QuickJS runtime");
    drop(runtime);
}

#[wasm_export]
pub fn set_memory_limit_runtime(runtime: &Runtime, limit: u64) {
    debug!("Setting QuickJSRuntime memory limit to {} bytes", limit);
    runtime.set_memory_limit(limit as usize);
}

#[link(wasm_import_module = "env")]
extern "C" {
    pub fn handle_rejected_promise(
        context_ptr: u64,
        promise_ptr: u64,
        reason_ptr: u32,
        reason_len: u32,
        is_handled: u32
    );
}

#[repr(C)]
pub struct StringResult {
    pub ptr: u32,
    pub len: u32
}

#[link(wasm_import_module = "env")]
extern "C" {
    pub fn resolve_module(
        base_ptr: u32,
        base_length: u32,
        name_ptr: u32,
        name_length: u32,
        out_result: u32
    );

    pub fn load_module(
        name_ptr: u32,
        name_length: u32,
        out_result: u32
    );
}

pub struct JavaResolver;
impl Resolver for JavaResolver {
    fn resolve<'js>(&mut self, _: &Ctx<'js>, base: &str, name: &str) -> rquickjs::Result<String> {
        let mut string_result = StringResult { ptr: 0, len: 0 };
        unsafe { resolve_module(
            base.as_ptr() as u32,
            base.len() as u32,
            name.as_ptr() as u32,
            name.len() as u32,
            &mut string_result as *mut StringResult as u32
        ) };

        if string_result.ptr == 0 { return Err(rquickjs::Error::new_resolving(base, name)); }

        let bytes = unsafe {
            std::slice::from_raw_parts(string_result.ptr as *const u8, string_result.len as usize)
        };

        match str::from_utf8(bytes) {
            Ok(s) => Ok(s.to_owned()),
            Err(e) => Err(rquickjs::Error::from(e))
        }
    }
}

pub struct JavaLoader;
impl Loader for JavaLoader {
    fn load<'js>(&mut self, ctx: &Ctx<'js>, name: &str) -> rquickjs::Result<Module<'js, Declared>> {
        let mut string_result = StringResult { ptr: 0, len: 0 };
        unsafe { load_module(
            name.as_ptr() as u32,
            name.len() as u32,
            &mut string_result as *mut StringResult as u32
        ) };

        if string_result.ptr == 0 { return Err(rquickjs::Error::new_loading(name)); }

        let bytes = unsafe {
            std::slice::from_raw_parts(string_result.ptr as *const u8, string_result.len as usize)
        };

        match str::from_utf8(bytes) {
            Ok(s) => Module::declare(ctx.clone(), name, s),
            Err(e) => Err(rquickjs::Error::from(e))
        }
    }
}

#[wasm_export]
pub fn set_loader(runtime: &Runtime) {
    runtime.set_loader(JavaResolver, JavaLoader);
}
