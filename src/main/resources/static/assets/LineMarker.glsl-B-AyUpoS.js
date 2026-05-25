import{zK as ee,I7 as te,I8 as ie,GH as re,GF as ae,I9 as se,Ia as oe,GK as o,I1 as ne,Hi as ce,I0 as le,GJ as w,Ib as pe,Ic as de,Hm as N,GI as he,GL as ve,GM as ue,HI as me,GO as fe,Id as j,Ie as E,If as ge,Ig as Se,Hn as Pe,lY as ye,GQ as _e,l_ as we,GR as ze,ag as xe,GS as L,GT as R,GU as $e,GV as H,GW as M,Hp as be,GY as Te,GZ as ke,G_ as Ve,H0 as k,H1 as Oe,Ih as De,Ii as U,Ij as B,Ik as We,Il as Ce,Im as Le,_ as c,e as Me,H2 as Ie,H3 as d,k3 as Ae,Hq as Fe,In as q,va as Ge,HT as Ne,Io as je,H6 as Ee,cE as Y,cM as Re,kn as K,cF as Q,Ip as He}from"./index-CvNg61sS.js";function J(t){const e=new ee,{space:r,anchor:i,hasTip:P,hasScreenSizePerspective:z}=t,u=r===2,g=r===1;e.include(te,t),e.include(ie,t),e.include(re,t);const{vertex:a,fragment:S,varyings:y}=e;ae(a,t),e.attributes.add("position","vec3"),e.attributes.add("previousDelta","vec4"),e.attributes.add("uv0","vec2"),y.add("vColor","vec4"),y.add("vpos","vec3",{invariant:!0}),y.add("vUV","vec2"),y.add("vSize","float"),P&&y.add("vLineWidth","float"),a.uniforms.add(new se("nearFar",({camera:p})=>p.nearFar),new oe("viewport",({camera:p})=>p.fullViewport)).code.add(o`vec4 projectAndScale(vec4 pos) {
vec4 posNdc = proj * pos;
posNdc.xy *= viewport.zw / posNdc.w;
return posNdc;
}`),a.code.add(o`void clip(vec4 pos, inout vec4 prev) {
float vnp = nearFar[0] * 0.99;
if (prev.z > -nearFar[0]) {
float interpolation = (-vnp - pos.z) / (prev.z - pos.z);
prev = mix(pos, prev, interpolation);
}
}`),u?(e.attributes.add("normal","vec3"),ne(a),a.constants.add("tiltThreshold","float",.7),a.code.add(o`vec3 perpendicular(vec3 v) {
vec3 n = (viewNormal * vec4(normal.xyz, 1.0)).xyz;
vec3 n2 = cross(v, n);
vec3 forward = vec3(0.0, 0.0, 1.0);
float tiltDot = dot(forward, n);
return abs(tiltDot) < tiltThreshold ? n : n2;
}`)):a.code.add(o`vec2 perpendicular(vec2 v) {
return vec2(v.y, -v.x);
}`);const h=u?"vec3":"vec2";return a.code.add(o`
      ${h} normalizedSegment(${h} pos, ${h} prev) {
        ${h} segment = pos - prev;
        float segmentLen = length(segment);

        // normalize or zero if too short
        return (segmentLen > 0.001) ? segment / segmentLen : ${u?"vec3(0.0, 0.0, 0.0)":"vec2(0.0, 0.0)"};
      }

      ${h} displace(${h} pos, ${h} prev, float displacementLen) {
        ${h} segment = normalizedSegment(pos, prev);

        ${h} displacementDirU = perpendicular(segment);
        ${h} displacementDirV = segment;

        ${i===1?"pos -= 0.5 * displacementLen * displacementDirV;":""}

        return pos + displacementLen * (uv0.x * displacementDirU + uv0.y * displacementDirV);
      }
    `),g&&(a.uniforms.add(new ce("inverseProjectionMatrix",({camera:p})=>p.inverseProjectionMatrix)),a.code.add(o`vec3 inverseProject(vec4 posScreen) {
posScreen.xy = (posScreen.xy / viewport.zw) * posScreen.w;
return (inverseProjectionMatrix * posScreen).xyz;
}`),a.code.add(o`bool rayIntersectPlane(vec3 rayDir, vec3 planeOrigin, vec3 planeNormal, out vec3 intersection) {
float cos = dot(rayDir, planeNormal);
float t = dot(planeOrigin, planeNormal) / cos;
intersection = t * rayDir;
return abs(cos) > 0.001 && t > 0.0;
}`),a.uniforms.add(new le("perScreenPixelRatio",({camera:p})=>p.perScreenPixelRatio)),a.code.add(o`
      vec4 toFront(vec4 displacedPosScreen, vec3 posLeft, vec3 posRight, vec3 prev, float lineWidth) {
        // Project displaced position back to camera space
        vec3 displacedPos = inverseProject(displacedPosScreen);

        // Calculate the plane that we want the marker to lie in. Note that this will always be an approximation since ribbon lines are generally
        // not planar and we do not know the actual position of the displaced prev vertices (they are offset in screen space, too).
        vec3 planeNormal = normalize(cross(posLeft - posRight, posLeft - prev));
        vec3 planeOrigin = posLeft;

        ${w(t.hasCap,`if(prev.z > posLeft.z) {
                vec2 diff = posLeft.xy - posRight.xy;
                planeOrigin.xy += perpendicular(diff) / 2.0;
             }`)};

        // Move the plane towards the camera by a margin dependent on the line width (approximated in world space). This tolerance corrects for the
        // non-planarity in most cases, but sharp joins can place the prev vertices at arbitrary positions so markers can still clip.
        float offset = lineWidth * perScreenPixelRatio;
        planeOrigin *= (1.0 - offset);

        // Intersect camera ray with the plane and make sure it is within clip space
        vec3 rayDir = normalize(displacedPos);
        vec3 intersection;
        if (rayIntersectPlane(rayDir, planeOrigin, planeNormal, intersection) && intersection.z < -nearFar[0] && intersection.z > -nearFar[1]) {
          return vec4(intersection.xyz, 1.0);
        }

        // Fallback: use depth of pos or prev, whichever is closer to the camera
        float minDepth = planeOrigin.z > prev.z ? length(planeOrigin) : length(prev);
        displacedPos *= minDepth / length(displacedPos);
        return vec4(displacedPos.xyz, 1.0);
      }
  `)),pe(a),e.include(de),a.main.add(o`
    // Check for special value of uv0.y which is used by the Renderer when graphics
    // are removed before the VBO is recompacted. If this is the case, then we just
    // project outside of clip space.
    if (uv0.y == 0.0) {
      // Project out of clip space
      gl_Position = ${N};
    }
    else {
      vec4 pos  = view * vec4(position, 1.0);
      vec4 prev = view * vec4(position + previousDelta.xyz * previousDelta.w, 1.0);

      float lineWidth = getLineWidth(${w(z,"pos.xyz")});
      float screenMarkerSize = getScreenMarkerSize(lineWidth);

      clip(pos, prev);

      ${u?o`${w(t.hideOnShortSegments,o`
                if (areWorldMarkersHidden(pos.xyz, prev.xyz)) {
                  gl_Position = ${N};
                  return;
                }`)}
            pos.xyz = displace(pos.xyz, prev.xyz, getWorldMarkerSize(pos.xyz));
            vec4 displacedPosScreen = projectAndScale(pos);`:o`
            vec4 posScreen = projectAndScale(pos);
            vec4 prevScreen = projectAndScale(prev);
            vec4 displacedPosScreen = posScreen;

            displacedPosScreen.xy = displace(posScreen.xy, prevScreen.xy, screenMarkerSize);
            ${w(g,o`
                vec2 displacementDirU = perpendicular(normalizedSegment(posScreen.xy, prevScreen.xy));

                // We need three points of the ribbon line in camera space to calculate the plane it lies in
                // Note that we approximate the third point, since we have no information about the join around prev
                vec3 lineRight = inverseProject(posScreen + lineWidth * vec4(displacementDirU.xy, 0.0, 0.0));
                vec3 lineLeft = pos.xyz + (pos.xyz - lineRight);

                pos = toFront(displacedPosScreen, lineLeft, lineRight, prev.xyz, lineWidth);
                displacedPosScreen = projectAndScale(pos);`)}`}
      forwardViewPosDepth(pos.xyz);
      // Convert back into NDC
      displacedPosScreen.xy = (displacedPosScreen.xy / viewport.zw) * displacedPosScreen.w;

      // Convert texture coordinate into [0,1]
      vUV = (uv0 + 1.0) / 2.0;
      ${w(!u,"vUV = noPerspectiveWrite(vUV, displacedPosScreen.w);")}
      ${w(P,"vLineWidth = noPerspectiveWrite(lineWidth, displacedPosScreen.w);")}

      vSize = screenMarkerSize;
      vColor = getColor();

      // Use camera space for slicing
      vpos = pos.xyz;

      gl_Position = displacedPosScreen;
    }`),S.include(he,t),e.include(ve,t),S.include(ue),S.uniforms.add(new me("intrinsicColor",({color:p})=>p),new fe("tex",({markerTexture:p})=>p)).constants.add("texelSize","float",1/j).code.add(o`float markerAlpha(vec2 samplePos) {
samplePos += vec2(0.5, -0.5) * texelSize;
float sdf = texture(tex, samplePos).r;
float pixelDistance = sdf * vSize;
pixelDistance -= 0.5;
return clamp(0.5 - pixelDistance, 0.0, 1.0);
}`),P&&(e.include(E),S.constants.add("relativeMarkerSize","float",ge/j).constants.add("relativeTipLineWidth","float",Se).code.add(o`
    float tipAlpha(vec2 samplePos) {
      // Convert coordinates s.t. they are in pixels and relative to the tip of an arrow marker
      samplePos -= vec2(0.5, 0.5 + 0.5 * relativeMarkerSize);
      samplePos *= vSize;

      float halfMarkerSize = 0.5 * relativeMarkerSize * vSize;
      float halfTipLineWidth = 0.5 * max(1.0, relativeTipLineWidth * noPerspectiveRead(vLineWidth));

      ${w(u,"halfTipLineWidth *= fwidth(samplePos.y);")}

      float distance = max(abs(samplePos.x) - halfMarkerSize, abs(samplePos.y) - halfTipLineWidth);
      return clamp(0.5 - distance, 0.0, 1.0);
    }
  `)),e.include(Pe,t),e.include(E),S.main.add(o`
    discardBySlice(vpos);
    discardByTerrainDepth();

    vec4 finalColor = intrinsicColor * vColor;

    // Cancel out perspective correct interpolation if in screen space or draped
    vec2 samplePos = ${w(!u,"noPerspectiveRead(vUV)","vUV")};
    finalColor.a *= ${P?"max(markerAlpha(samplePos), tipAlpha(samplePos))":"markerAlpha(samplePos)"};
    outputColorHighlightOLID(applySlice(finalColor, vpos), finalColor.rgb);`),e}const Ue=Object.freeze(Object.defineProperty({__proto__:null,build:J},Symbol.toStringTag,{value:"Module"}));let I=class extends _e{constructor(t,e){super(t,e,we(Z(e))),this.shader=new ze(Ue,()=>xe(()=>Promise.resolve().then(()=>Je),void 0))}_makePipelineState(t,e){const{output:r,hasEmission:i,oitPass:P,space:z,hasOccludees:u}=t;return L({blending:k(r)?Oe(P):null,depthTest:z===0?null:Ve(P),depthWrite:ke(t),drawBuffers:be(r,Te(P,i)),colorWrite:M,stencilWrite:u?H:null,stencilTest:u?e?R:$e:null,polygonOffset:{factor:0,units:-10}})}initializePipeline(t){return t.occluder?(this._occluderPipelineTransparent=L({blending:B,depthTest:U,depthWrite:null,colorWrite:M,stencilWrite:null,stencilTest:De}),this._occluderPipelineOpaque=L({blending:B,depthTest:U,depthWrite:null,colorWrite:M,stencilWrite:Ce,stencilTest:We}),this._occluderPipelineMaskWrite=L({blending:null,depthTest:Le,depthWrite:null,colorWrite:null,stencilWrite:H,stencilTest:R})):this._occluderPipelineTransparent=this._occluderPipelineOpaque=this._occluderPipelineMaskWrite=null,this._occludeePipelineState=this._makePipelineState(t,!0),this._makePipelineState(t,!1)}getPipeline(t,e){return e?this._occludeePipelineState:t.occluder===12?this._occluderPipelineTransparent??super.getPipeline(t):t.occluder===11?this._occluderPipelineOpaque??super.getPipeline(t):this._occluderPipelineMaskWrite??super.getPipeline(t)}};function Z(t){const e=ye().vec3f("position").vec4f16("previousDelta").vec2f16("uv0");return t.hasVVColor?e.f32("colorFeatureAttribute"):e.vec4u8("color",{glNormalized:!0}),t.hasVVOpacity&&e.f32("opacityFeatureAttribute"),t.hasVVSize?e.f32("sizeFeatureAttribute"):e.f16("size"),t.worldSpace&&e.vec3f16("normal"),e.freeze()}I=c([Me("esri.views.3d.webgl-engine.shaders.LineMarkerTechnique")],I);class l extends Ie{constructor(e){super(),this.spherical=e,this.space=1,this.anchor=0,this.occluder=!1,this.writeDepth=!1,this.hideOnShortSegments=!1,this.hasCap=!1,this.hasTip=!1,this.hasVVSize=!1,this.hasVVColor=!1,this.hasVVOpacity=!1,this.hasOccludees=!1,this.terrainDepthTest=!1,this.cullAboveTerrain=!1,this.hasScreenSizePerspective=!1,this.textureCoordinateType=0,this.emissionSource=0,this.discardInvisibleFragments=!0,this.occlusionPass=!1,this.hasVVInstancing=!1,this.hasSliceTranslatedView=!0,this.olidColorInstanced=!1,this.overlayEnabled=!1,this.snowCover=!1}get draped(){return this.space===0}get worldSpace(){return this.space===2}}c([d({count:3})],l.prototype,"space",void 0),c([d({count:2})],l.prototype,"anchor",void 0),c([d()],l.prototype,"occluder",void 0),c([d()],l.prototype,"writeDepth",void 0),c([d()],l.prototype,"hideOnShortSegments",void 0),c([d()],l.prototype,"hasCap",void 0),c([d()],l.prototype,"hasTip",void 0),c([d()],l.prototype,"hasVVSize",void 0),c([d()],l.prototype,"hasVVColor",void 0),c([d()],l.prototype,"hasVVOpacity",void 0),c([d()],l.prototype,"hasOccludees",void 0),c([d()],l.prototype,"terrainDepthTest",void 0),c([d()],l.prototype,"cullAboveTerrain",void 0),c([d()],l.prototype,"hasScreenSizePerspective",void 0);class Xe extends Ae{constructor(e,r){super(e,qe),this.produces=new Map([[2,i=>i===8||k(i)&&this.parameters.renderOccluded===8],[3,i=>Fe(i)],[11,i=>q(i)&&this.parameters.renderOccluded===8],[12,i=>q(i)&&this.parameters.renderOccluded===8],[4,i=>k(i)&&this.parameters.writeDepth],[9,i=>k(i)&&!this.parameters.writeDepth],[20,i=>k(i)||i===8]]),this.intersectDraped=void 0,this._configuration=new l(r)}getConfiguration(e,r){return super.getConfiguration(e,r,this._configuration),this._configuration.space=r.slot===20?0:this.parameters.worldSpace?2:1,this._configuration.hideOnShortSegments=this.parameters.hideOnShortSegments,this._configuration.hasCap=this.parameters.cap!==0,this._configuration.anchor=this.parameters.anchor,this._configuration.hasTip=this.parameters.hasTip,this._configuration.hasSlicePlane=this.parameters.hasSlicePlane,this._configuration.hasOccludees=r.hasOccludees,this._configuration.writeDepth=this.parameters.writeDepth,this._configuration.hasVVSize=this.parameters.hasVVSize,this._configuration.hasVVColor=this.parameters.hasVVColor,this._configuration.hasVVOpacity=this.parameters.hasVVOpacity,this._configuration.occluder=this.parameters.renderOccluded===8,this._configuration.terrainDepthTest=r.terrainDepthTest&&k(e),this._configuration.cullAboveTerrain=r.cullAboveTerrain,this._configuration.hasScreenSizePerspective=this.parameters.screenSizePerspective!=null,this._configuration}get visible(){return this.parameters.color[3]>=Ge}intersect(){}createBufferWriter(){return new Ye(Z(this.parameters),this.parameters)}createGLMaterial(e){return new Be(e)}}class Be extends Ee{dispose(){super.dispose(),this._markerTextures?.release(this._markerPrimitive),this._markerPrimitive=null}beginSlot(e){const r=this._material.parameters.markerPrimitive;return r!==this._markerPrimitive&&(this._material.setParameters({markerTexture:this._markerTextures.swap(r,this._markerPrimitive)}),this._markerPrimitive=r),this.getTechnique(I,e)}}class qe extends Ne{constructor(){super(...arguments),this.width=0,this.color=[1,1,1,1],this.markerPrimitive="arrow",this.placement="end",this.cap=0,this.anchor=0,this.hasTip=!1,this.worldSpace=!1,this.hideOnShortSegments=!1,this.writeDepth=!0,this.hasSlicePlane=!1,this.vvFastUpdate=!1,this.stipplePattern=null,this.markerTexture=null}}class Ye{constructor(e,r){this.layout=e,this._parameters=r}elementCount(){return this._parameters.placement==="begin-end"?12:6}write(e,r,i,P,z,u){const g=i.get("position").data,a=g.length/3;let S=[1,0,0];const y=i.get("normal");this._parameters.worldSpace&&y!=null&&(S=y.data);let h=1,p=0;this._parameters.vvSize?p=i.get("sizeFeatureAttribute").data[0]:i.has("size")&&(h=i.get("size").data[0]);let $=[1,1,1,1],A=0;this._parameters.vvColor?A=i.get("colorFeatureAttribute").data[0]:i.has("color")&&($=i.get("color").data);let F=0;this._parameters.vvOpacity&&(F=i.get("opacityFeatureAttribute").data[0]);const x=new Float32Array(z.buffer),_=je(z.buffer),V=new Uint8Array(z.buffer);let v=u*(this.layout.stride/4);const b=x.BYTES_PER_ELEMENT/_.BYTES_PER_ELEMENT,X=4/b,T=(n,D,m,f)=>{x[v++]=n[0],x[v++]=n[1],x[v++]=n[2],He(D,n,_,v*b),v+=X;let s=v*b;if(_[s++]=m[0],_[s++]=m[1],v=Math.ceil(s/b),this._parameters.vvColor)x[v++]=A;else{const W=Math.min(4*f,$.length-4),C=4*v++;V[C]=255*$[W],V[C+1]=255*$[W+1],V[C+2]=255*$[W+2],V[C+3]=255*$[W+3]}this._parameters.vvOpacity&&(x[v++]=F),s=v*b,this._parameters.vvSize?(x[v++]=p,s+=2):_[s++]=h,this._parameters.worldSpace&&(_[s++]=S[0],_[s++]=S[1],_[s++]=S[2]),v=Math.ceil(s/b)},G=(n,D)=>{const m=Y(Ke,g[3*n],g[3*n+1],g[3*n+2]),f=Qe;let s=n+D;do Y(f,g[3*s],g[3*s+1],g[3*s+2]),s+=D;while(Re(m,f)&&s>=0&&s<a);e&&(K(m,m,e),K(f,f,e)),T(m,f,[-1,-1],n),T(m,f,[1,-1],n),T(m,f,[1,1],n),T(m,f,[-1,-1],n),T(m,f,[1,1],n),T(m,f,[-1,1],n)},O=this._parameters.placement;return O!=="begin"&&O!=="begin-end"||G(0,1),O!=="end"&&O!=="begin-end"||G(a-1,-1),null}}const Ke=Q(),Qe=Q(),Je=Object.freeze(Object.defineProperty({__proto__:null,build:J},Symbol.toStringTag,{value:"Module"}));export{Xe as g};
