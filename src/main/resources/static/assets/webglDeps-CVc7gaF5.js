import{tC as l,vq as m,mX as d,GE as b,F9 as x,iF as y,ra as F}from"./index-CvNg61sS.js";import{e as j}from"./ShaderCompiler-G2XYGDs6.js";import{e as C}from"./ProgramTemplate-Gd6Ckm5j.js";function c(r){const{options:e,value:n}=r;return typeof e[n]=="number"}function p(r){let e="";for(const n in r){const o=r[n];if(typeof o=="boolean")o&&(e+=`#define ${n}
`);else if(typeof o=="number")e+=`#define ${n} ${o.toFixed()}
`;else if(typeof o=="object")if(c(o)){const{value:t,options:f,namespace:a}=o,i=a?`${a}_`:"";for(const s in f)e+=`#define ${i}${s} ${f[s].toFixed()}
`;e+=`#define ${n} ${i}${t}
`}else{const t=o.options;let f=0;for(const a in t)e+=`#define ${t[a]} ${(f++).toFixed()}
`;e+=`#define ${n} ${t[o.value]}
`}}return e}export{l as BufferObject,m as FramebufferObject,d as Program,b as ProgramCache,x as Renderbuffer,j as ShaderCompiler,y as Texture,F as VertexArrayObject,C as createProgram,p as glslifyDefineMap};
