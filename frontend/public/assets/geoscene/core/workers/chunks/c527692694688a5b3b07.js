"use strict";(self.webpackChunkRemoteClient=self.webpackChunkRemoteClient||[]).push([[6472],{83840:(e,t,r)=>{r.d(t,{D:()=>z,b:()=>V});var i=r(90124),o=r(97760),a=r(80324),n=r(68828),s=r(70984),l=r(11925),c=r(88196),d=r(32704),u=r(49168),h=r(55164),m=r(41804),f=r(60072),p=r(11544),v=r(2288),g=r(87836),_=r(23792),x=r(57704),T=r(53672),E=r(50143),b=r(58472),S=r(36908),A=r(81832),y=r(64352),C=r(95128),w=r(36244),M=r(74072),R=r(93904),I=r(29484),O=r(95224),N=r(56124),P=r(56192),L=r(95464),D=r(60288),F=r(14920),U=r(49916),G=r(56336),B=r(4636);function V(e){const t=new F.i0,{vertex:r,fragment:V,varyings:z}=t;if((0,O.IE)(r,e),t.include(d.s),z.add("vpos","vec3"),t.include(w.a,e),t.include(l.As,e),t.include(p.k,e),t.include(C.wZ,e),e.output===a.mC.Color||e.output===a.mC.Alpha){t.include(C.Wq,e),t.include(C.Qr,e),t.include(C.U_,e),t.include(C.UJ,e),(0,O.E7)(r,e),t.include(c.k,e),t.include(s.w,e);const a=e.normalType===c.g.Attribute||e.normalType===c.g.Compressed;a&&e.offsetBackfaces&&t.include(o.m),t.include(g.W,e),t.include(f.u8,e),e.instancedColor&&t.attributes.add(B.K.INSTANCECOLOR,"vec4"),z.add("vPositionLocal","vec3"),t.include(h.I,e),t.include(i.Er,e),t.include(u.i,e),t.include(m.O,e),r.uniforms.add(new P.U("externalColor",(e=>e.externalColor))),z.add("vcolorExt","vec4"),e.multipassEnabled&&z.add("depth","float"),r.code.add(D.Y`
      void main(void) {
        forwardNormalizedVertexColor();
        vcolorExt = externalColor;
        ${e.instancedColor?"vcolorExt *= instanceColor * 0.003921568627451;":""}
        vcolorExt *= vvColor();
        vcolorExt *= getSymbolColor();
        forwardColorMixMode();

        if (vcolorExt.a < ${D.Y.float(M.A)}) {
          gl_Position = vec4(1e38, 1e38, 1e38, 1.0);
        } else {
          vpos = getVertexInLocalOriginSpace();
          vPositionLocal = vpos - view[3].xyz;
          vpos = subtractOrigin(vpos);
          ${a?D.Y`vNormalWorld = dpNormal(vvLocalNormal(normalModel()));`:""}
          vpos = addVerticalOffset(vpos, localOrigin);
          ${e.hasVertexTangents?"vTangent = dpTransformVertexTangent(tangent);":""}
          gl_Position = transformPosition(proj, view, vpos);
          ${a&&e.offsetBackfaces?"gl_Position = offsetBackfacingClipPosition(gl_Position, vpos, vNormalWorld, cameraPosition);":""}
        }

        ${e.multipassEnabled?"depth = (view * vec4(vpos, 1.0)).z;":""}
        forwardLinearDepth();
        forwardTextureCoordinates();
        forwardColorUV();
        forwardNormalUV();
        forwardEmissiveUV();
        forwardOcclusionUV();
        forwardMetallicRoughnessUV();
      }
    `)}switch(e.output){case a.mC.Alpha:t.include(n.A3,e),t.include(R.Q,e),t.include(E._,e),V.uniforms.add(new L.d("opacity",(e=>e.opacity)),new L.d("layerOpacity",(e=>e.layerOpacity))),e.hasColorTexture&&V.uniforms.add(new U.W("tex",(e=>e.texture))),V.include(I.u),V.code.add(D.Y`
      void main() {
        discardBySlice(vpos);
        ${e.multipassEnabled?"terrainDepthTest(depth);":""}
        ${e.hasColorTexture?D.Y`
                vec4 texColor = texture(tex, ${e.hasColorTextureTransform?D.Y`colorUV`:D.Y`vuv0`});
                ${e.textureAlphaPremultiplied?"texColor.rgb /= texColor.a;":""}
                discardOrAdjustAlpha(texColor);`:D.Y`vec4 texColor = vec4(1.0);`}
        ${e.hasVertexColors?D.Y`float opacity_ = layerOpacity * mixExternalOpacity(vColor.a * opacity, texColor.a, vcolorExt.a, int(colorMixMode));`:D.Y`float opacity_ = layerOpacity * mixExternalOpacity(opacity, texColor.a, vcolorExt.a, int(colorMixMode));`}
        fragColor = vec4(opacity_);
      }
    `);break;case a.mC.Color:t.include(n.A3,e),t.include(x.kf,e),t.include(_.i,e),t.include(R.Q,e),t.include(e.instancedDoublePrecision?y.i8:y.WK,e),t.include(E._,e),(0,O.E7)(V,e),V.uniforms.add(r.uniforms.get("localOrigin"),new N.C("ambient",(e=>e.ambient)),new N.C("diffuse",(e=>e.diffuse)),new L.d("opacity",(e=>e.opacity)),new L.d("layerOpacity",(e=>e.layerOpacity))),e.hasColorTexture&&V.uniforms.add(new U.W("tex",(e=>e.texture))),t.include(A.G4,e),t.include(S.I,e),V.include(I.u),t.include(b.u,e),(0,x.I4)(V),(0,x.oZ)(V),(0,T.o1)(V),V.code.add(D.Y`
      void main() {
        discardBySlice(vpos);
        ${e.multipassEnabled?"terrainDepthTest(depth);":""}
        ${e.hasColorTexture?D.Y`
                vec4 texColor = texture(tex, ${e.hasColorTextureTransform?D.Y`colorUV`:D.Y`vuv0`});
                ${e.textureAlphaPremultiplied?"texColor.rgb /= texColor.a;":""}
                discardOrAdjustAlpha(texColor);`:D.Y`vec4 texColor = vec4(1.0);`}
        shadingParams.viewDirection = normalize(vpos - cameraPosition);
        ${e.normalType===c.g.ScreenDerivative?D.Y`
                vec3 normal = screenDerivativeNormal(vPositionLocal);`:D.Y`
                shadingParams.normalView = vNormalWorld;
                vec3 normal = shadingNormal(shadingParams);`}
        ${e.pbrMode===A.Ax.Normal?"applyPBRFactors();":""}
        float ssao = evaluateAmbientOcclusionInverse() * getBakedOcclusion();

        vec3 posWorld = vpos + localOrigin;

        float additionalAmbientScale = additionalDirectedAmbientLight(posWorld);
        float shadow = ${e.receiveShadows?"readShadowMap(vpos, linearDepth)":e.spherical?"lightingGlobalFactor * (1.0 - additionalAmbientScale)":"0.0"};

        vec3 matColor = max(ambient, diffuse);
        ${e.hasVertexColors?D.Y`
                vec3 albedo = mixExternalColor(vColor.rgb * matColor, texColor.rgb, vcolorExt.rgb, int(colorMixMode));
                float opacity_ = layerOpacity * mixExternalOpacity(vColor.a * opacity, texColor.a, vcolorExt.a, int(colorMixMode));`:D.Y`
                vec3 albedo = mixExternalColor(matColor, texColor.rgb, vcolorExt.rgb, int(colorMixMode));
                float opacity_ = layerOpacity * mixExternalOpacity(opacity, texColor.a, vcolorExt.a, int(colorMixMode));`}
        ${e.hasNormalTexture?D.Y`
                mat3 tangentSpace = ${e.hasVertexTangents?"computeTangentSpace(normal);":"computeTangentSpace(normal, vpos, vuv0);"}
                vec3 shadingNormal = computeTextureNormal(tangentSpace, ${e.hasNormalTextureTransform?D.Y`normalUV`:"vuv0"});`:D.Y`vec3 shadingNormal = normal;`}
        vec3 normalGround = ${e.spherical?D.Y`normalize(posWorld);`:D.Y`vec3(0.0, 0.0, 1.0);`}

        ${e.snowCover?D.Y`
                float snow = smoothstep(0.5, 0.55, dot(normal, normalGround));
                albedo = mix(albedo, vec3(1), snow);
                shadingNormal = mix(shadingNormal, normal, snow);
                ssao = mix(ssao, 1.0, snow);`:""}

        vec3 additionalLight = ssao * mainLightIntensity * additionalAmbientScale * ambientBoostFactor * lightingGlobalFactor;

        ${e.pbrMode===A.Ax.Normal||e.pbrMode===A.Ax.Schematic?D.Y`
                float additionalAmbientIrradiance = additionalAmbientIrradianceFactor * mainLightIntensity[2];
                ${e.snowCover?D.Y`
                        mrr = mix(mrr, vec3(0.0, 1.0, 0.04), snow);
                        emission = mix(emission, vec3(0.0), snow);`:""}

                vec3 shadedColor = evaluateSceneLightingPBR(shadingNormal, albedo, shadow, 1.0 - ssao, additionalLight, shadingParams.viewDirection, normalGround, mrr, emission, additionalAmbientIrradiance);`:D.Y`vec3 shadedColor = evaluateSceneLighting(shadingNormal, albedo, shadow, 1.0 - ssao, additionalLight);`}
        fragColor = highlightSlice(vec4(shadedColor, opacity_), vpos);
        ${e.transparencyPassType===G.H.Color?D.Y`fragColor = premultiplyAlpha(fragColor);`:""}
      }
    `)}return t.include(v.y,e),t}const z=Object.freeze(Object.defineProperty({__proto__:null,build:V},Symbol.toStringTag,{value:"Module"}))},59888:(e,t,r)=>{r.d(t,{R:()=>U,b:()=>F});var i=r(90124),o=r(97760),a=r(80324),n=r(68828),s=r(70984),l=r(11925),c=r(88196),d=r(32704),u=r(49168),h=r(55164),m=r(41804),f=r(11544),p=r(2288),v=r(23792),g=r(57704),_=r(53672),x=r(50143),T=r(36908),E=r(81832),b=r(64352),S=r(36244),A=r(74072),y=r(93904),C=r(29484),w=r(95224),M=r(56124),R=r(56192),I=r(95464),O=r(60288),N=r(14920),P=r(49916),L=r(56336),D=r(4636);function F(e){const t=new N.i0,{vertex:r,fragment:F,varyings:U}=t;return(0,w.IE)(r,e),t.include(d.s),U.add("vpos","vec3"),t.include(S.a,e),t.include(l.As,e),t.include(f.k,e),e.output!==a.mC.Color&&e.output!==a.mC.Alpha||((0,w.E7)(t.vertex,e),t.include(c.k,e),t.include(s.w,e),e.offsetBackfaces&&t.include(o.m),e.instancedColor&&t.attributes.add(D.K.INSTANCECOLOR,"vec4"),U.add("vNormalWorld","vec3"),U.add("localvpos","vec3"),e.multipassEnabled&&U.add("depth","float"),t.include(h.I,e),t.include(i.Er,e),t.include(u.i,e),t.include(m.O,e),r.uniforms.add(new R.U("externalColor",(e=>e.externalColor))),U.add("vcolorExt","vec4"),r.code.add(O.Y`
        void main(void) {
          forwardNormalizedVertexColor();
          vcolorExt = externalColor;
          ${e.instancedColor?"vcolorExt *= instanceColor * 0.003921568627451;":""}
          vcolorExt *= vvColor();
          vcolorExt *= getSymbolColor();
          forwardColorMixMode();

          if (vcolorExt.a < ${O.Y.float(A.A)}) {
            gl_Position = vec4(1e38, 1e38, 1e38, 1.0);
          } else {
            vpos = getVertexInLocalOriginSpace();
            localvpos = vpos - view[3].xyz;
            vpos = subtractOrigin(vpos);
            vNormalWorld = dpNormal(vvLocalNormal(normalModel()));
            vpos = addVerticalOffset(vpos, localOrigin);
            gl_Position = transformPosition(proj, view, vpos);
            ${e.offsetBackfaces?"gl_Position = offsetBackfacingClipPosition(gl_Position, vpos, vNormalWorld, cameraPosition);":""}
          }
          ${e.multipassEnabled?O.Y`depth = (view * vec4(vpos, 1.0)).z;`:""}
          forwardLinearDepth();
          forwardTextureCoordinates();
        }
      `)),e.output===a.mC.Alpha&&(t.include(n.A3,e),t.include(y.Q,e),t.include(x._,e),F.uniforms.add(new I.d("opacity",(e=>e.opacity)),new I.d("layerOpacity",(e=>e.layerOpacity))),e.hasColorTexture&&F.uniforms.add(new P.W("tex",(e=>e.texture))),F.include(C.u),F.code.add(O.Y`
      void main() {
        discardBySlice(vpos);
        ${e.multipassEnabled?O.Y`terrainDepthTest(depth);`:""}
        ${e.hasColorTexture?O.Y`
                vec4 texColor = texture(tex, ${e.hasColorTextureTransform?O.Y`colorUV`:O.Y`vuv0`});
                ${e.textureAlphaPremultiplied?"texColor.rgb /= texColor.a;":""}
                discardOrAdjustAlpha(texColor);`:O.Y`vec4 texColor = vec4(1.0);`}
        ${e.hasVertexColors?O.Y`float opacity_ = layerOpacity * mixExternalOpacity(vColor.a * opacity, texColor.a, vcolorExt.a, int(colorMixMode));`:O.Y`float opacity_ = layerOpacity * mixExternalOpacity(opacity, texColor.a, vcolorExt.a, int(colorMixMode));`}

        fragColor = vec4(opacity_);
      }
    `)),e.output===a.mC.Color&&(t.include(n.A3,e),t.include(g.kf,e),t.include(v.i,e),t.include(y.Q,e),t.include(e.instancedDoublePrecision?b.i8:b.WK,e),t.include(x._,e),(0,w.E7)(t.fragment,e),(0,_.OB)(F),(0,g.I4)(F),(0,g.oZ)(F),F.uniforms.add(r.uniforms.get("localOrigin"),r.uniforms.get("view"),new M.C("ambient",(e=>e.ambient)),new M.C("diffuse",(e=>e.diffuse)),new I.d("opacity",(e=>e.opacity)),new I.d("layerOpacity",(e=>e.layerOpacity))),e.hasColorTexture&&F.uniforms.add(new P.W("tex",(e=>e.texture))),t.include(E.G4,e),t.include(T.I,e),F.include(C.u),(0,_.o1)(F),F.code.add(O.Y`
      void main() {
        discardBySlice(vpos);
        ${e.multipassEnabled?O.Y`terrainDepthTest(depth);`:""}
        ${e.hasColorTexture?O.Y`
                vec4 texColor = texture(tex, ${e.hasColorTextureTransform?O.Y`colorUV`:O.Y`vuv0`});
                ${e.textureAlphaPremultiplied?"texColor.rgb /= texColor.a;":""}
                discardOrAdjustAlpha(texColor);`:O.Y`vec4 texColor = vec4(1.0);`}
        vec3 viewDirection = normalize(vpos - cameraPosition);
        ${e.pbrMode===E.Ax.Normal?"applyPBRFactors();":""}
        float ssao = evaluateAmbientOcclusionInverse();
        ssao *= getBakedOcclusion();

        float additionalAmbientScale = additionalDirectedAmbientLight(vpos + localOrigin);
        vec3 additionalLight = ssao * mainLightIntensity * additionalAmbientScale * ambientBoostFactor * lightingGlobalFactor;
        ${e.receiveShadows?"float shadow = readShadowMap(vpos, linearDepth);":e.spherical?"float shadow = lightingGlobalFactor * (1.0 - additionalAmbientScale);":"float shadow = 0.0;"}
        vec3 matColor = max(ambient, diffuse);
        ${e.hasVertexColors?O.Y`
                vec3 albedo = mixExternalColor(vColor.rgb * matColor, texColor.rgb, vcolorExt.rgb, int(colorMixMode));
                float opacity_ = layerOpacity * mixExternalOpacity(vColor.a * opacity, texColor.a, vcolorExt.a, int(colorMixMode));`:O.Y`
                vec3 albedo = mixExternalColor(matColor, texColor.rgb, vcolorExt.rgb, int(colorMixMode));
                float opacity_ = layerOpacity * mixExternalOpacity(opacity, texColor.a, vcolorExt.a, int(colorMixMode));`}
        ${e.snowCover?O.Y`albedo = mix(albedo, vec3(1), 0.9);`:O.Y``}
        ${O.Y`
            vec3 shadingNormal = normalize(vNormalWorld);
            albedo *= 1.2;
            vec3 viewForward = vec3(view[0][2], view[1][2], view[2][2]);
            float alignmentLightView = clamp(dot(viewForward, -mainLightDirection), 0.0, 1.0);
            float transmittance = 1.0 - clamp(dot(viewForward, shadingNormal), 0.0, 1.0);
            float treeRadialFalloff = vColor.r;
            float backLightFactor = 0.5 * treeRadialFalloff * alignmentLightView * transmittance * (1.0 - shadow);
            additionalLight += backLightFactor * mainLightIntensity;`}
        ${e.pbrMode===E.Ax.Normal||e.pbrMode===E.Ax.Schematic?e.spherical?O.Y`vec3 normalGround = normalize(vpos + localOrigin);`:O.Y`vec3 normalGround = vec3(0.0, 0.0, 1.0);`:O.Y``}
        ${e.pbrMode===E.Ax.Normal||e.pbrMode===E.Ax.Schematic?O.Y`
                float additionalAmbientIrradiance = additionalAmbientIrradianceFactor * mainLightIntensity[2];
                ${e.snowCover?O.Y`
                        mrr = vec3(0.0, 1.0, 0.04);
                        emission = vec3(0.0);`:""}

                vec3 shadedColor = evaluateSceneLightingPBR(shadingNormal, albedo, shadow, 1.0 - ssao, additionalLight, viewDirection, normalGround, mrr, emission, additionalAmbientIrradiance);`:O.Y`vec3 shadedColor = evaluateSceneLighting(shadingNormal, albedo, shadow, 1.0 - ssao, additionalLight);`}
        fragColor = highlightSlice(vec4(shadedColor, opacity_), vpos);
        ${e.transparencyPassType===L.H.Color?O.Y`fragColor = premultiplyAlpha(fragColor);`:O.Y``}
      }
    `)),t.include(p.y,e),t}const U=Object.freeze(Object.defineProperty({__proto__:null,build:F},Symbol.toStringTag,{value:"Module"}))},81844:(e,t,r)=>{r.d(t,{S:()=>g,b:()=>f,g:()=>p});var i=r(37648),o=r(85256),a=r(99556),n=r(22340),s=r(252),l=r(48688),c=r(95464),d=r(60288),u=r(14920),h=r(49916);const m=16;function f(){const e=new u.i0,t=e.fragment;return e.include(a.Y),e.include(s.ig),t.include(n.W),t.uniforms.add(new c.d("radius",((e,t)=>p(t.camera)))),t.code.add(d.Y`vec3 sphere[16] = vec3[16](
vec3(0.186937, 0.0, 0.0),
vec3(0.700542, 0.0, 0.0),
vec3(-0.864858, -0.481795, -0.111713),
vec3(-0.624773, 0.102853, -0.730153),
vec3(-0.387172, 0.260319, 0.007229),
vec3(-0.222367, -0.642631, -0.707697),
vec3(-0.01336, -0.014956, 0.169662),
vec3(0.122575, 0.1544, -0.456944),
vec3(-0.177141, 0.85997, -0.42346),
vec3(-0.131631, 0.814545, 0.524355),
vec3(-0.779469, 0.007991, 0.624833),
vec3(0.308092, 0.209288,0.35969),
vec3(0.359331, -0.184533, -0.377458),
vec3(0.192633, -0.482999, -0.065284),
vec3(0.233538, 0.293706, -0.055139),
vec3(0.417709, -0.386701, 0.442449)
);
float fallOffFunction(float vv, float vn, float bias) {
float f = max(radius * radius - vv, 0.0);
return f * f * f * max(vn - bias, 0.0);
}`),t.code.add(d.Y`float aoValueFromPositionsAndNormal(vec3 C, vec3 n_C, vec3 Q) {
vec3 v = Q - C;
float vv = dot(v, v);
float vn = dot(normalize(v), n_C);
return fallOffFunction(vv, vn, 0.1);
}`),t.uniforms.add(new l.$("nearFar",((e,t)=>t.camera.nearFar)),new h.W("normalMap",(e=>e.normalTexture)),new h.W("depthMap",(e=>e.depthTexture)),new c.d("projScale",(e=>e.projScale)),new h.W("rnm",(e=>e.noiseTexture)),new l.$("rnmScale",((e,t)=>(0,i.WO)(v,t.camera.fullWidth/e.noiseTexture.descriptor.width,t.camera.fullHeight/e.noiseTexture.descriptor.height))),new c.d("intensity",(e=>e.intensity)),new l.$("screenSize",((e,t)=>(0,i.WO)(v,t.camera.fullWidth,t.camera.fullHeight)))),e.outputs.add("fragOcclusion","float"),t.code.add(d.Y`
    void main(void) {
      float currentPixelDepth = linearDepthFromTexture(depthMap, uv, nearFar);

      if (-currentPixelDepth > nearFar.y || -currentPixelDepth < nearFar.x) {
        fragOcclusion = 1.0;
        return;
      }

      // get the normal of current fragment
      vec4 norm4 = texture(normalMap, uv);
      if(norm4.a != 1.0) {
        fragOcclusion = 1.0;
        return;
      }
      vec3 norm = vec3(-1.0) + 2.0 * norm4.xyz;

      vec3 currentPixelPos = reconstructPosition(gl_FragCoord.xy, currentPixelDepth);

      float sum = 0.0;
      vec3 tapPixelPos;

      vec3 fres = normalize(2.0 * texture(rnm, uv * rnmScale).xyz - 1.0);

      // note: the factor 2.0 should not be necessary, but makes ssao much nicer.
      // bug or deviation from CE somewhere else?
      float ps = projScale / (2.0 * currentPixelPos.z * zScale.x + zScale.y);

      for(int i = 0; i < ${d.Y.int(m)}; ++i) {
        vec2 unitOffset = reflect(sphere[i], fres).xy;
        vec2 offset = vec2(-unitOffset * radius * ps);

        // don't use current or very nearby samples
        if( abs(offset.x) < 2.0 || abs(offset.y) < 2.0){
          continue;
        }

        vec2 tc = vec2(gl_FragCoord.xy + offset);
        if (tc.x < 0.0 || tc.y < 0.0 || tc.x > screenSize.x || tc.y > screenSize.y) continue;
        vec2 tcTap = tc / screenSize;
        float occluderFragmentDepth = linearDepthFromTexture(depthMap, tcTap, nearFar);

        tapPixelPos = reconstructPosition(tc, occluderFragmentDepth);

        sum += aoValueFromPositionsAndNormal(currentPixelPos, norm, tapPixelPos);
      }

      // output the result
      float A = max(1.0 - sum * intensity / float(${d.Y.int(m)}), 0.0);

      // Anti-tone map to reduce contrast and drag dark region farther: (x^0.2 + 1.2 * x^4) / 2.2
      A = (pow(A, 0.2) + 1.2 * A*A*A*A) / 2.2;

      fragOcclusion = A;
    }
  `),e}function p(e){return Math.max(10,20*e.computeScreenPixelSizeAtDist(Math.abs(4*e.relativeElevation)))}const v=(0,o.Su)(),g=Object.freeze(Object.defineProperty({__proto__:null,build:f,getRadius:p},Symbol.toStringTag,{value:"Module"}))},52404:(e,t,r)=>{r.d(t,{S:()=>p,b:()=>f});var i=r(98472),o=r(99556),a=r(22340),n=r(79216),s=r(48688),l=r(95464),c=r(60288),d=r(14920),u=r(83028),h=r(49916);const m=4;function f(){const e=new d.i0,t=e.fragment;e.include(o.Y);const r=(m+1)/2,f=1/(2*r*r);return t.include(a.W),t.uniforms.add(new h.W("depthMap",(e=>e.depthTexture)),new u.y("tex",(e=>e.colorTexture)),new n.O("blurSize",(e=>e.blurSize)),new l.d("projScale",((e,t)=>{const r=(0,i.q)(t.camera.eye,t.camera.center);return r>5e4?Math.max(0,e.projScale-(r-5e4)):e.projScale})),new s.$("nearFar",((e,t)=>t.camera.nearFar))),t.code.add(c.Y`
    void blurFunction(vec2 uv, float r, float center_d, float sharpness, inout float wTotal, inout float bTotal) {
      float c = texture(tex, uv).r;
      float d = linearDepthFromTexture(depthMap, uv, nearFar);

      float ddiff = d - center_d;

      float w = exp(-r * r * ${c.Y.float(f)} - ddiff * ddiff * sharpness);
      wTotal += w;
      bTotal += w * c;
    }
  `),e.outputs.add("fragBlur","float"),t.code.add(c.Y`
    void main(void) {
      float b = 0.0;
      float w_total = 0.0;

      float center_d = linearDepthFromTexture(depthMap, uv, nearFar);

      float sharpness = -0.05 * projScale / center_d;
      for (int r = -${c.Y.int(m)}; r <= ${c.Y.int(m)}; ++r) {
        float rf = float(r);
        vec2 uvOffset = uv + rf * blurSize;
        blurFunction(uvOffset, rf, center_d, sharpness, w_total, b);
      }

      fragBlur = b / w_total;
    }
  `),e}const p=Object.freeze(Object.defineProperty({__proto__:null,build:f},Symbol.toStringTag,{value:"Module"}))},84752:(e,t,r)=>{r.d(t,{a:()=>T,c:()=>x,d:()=>v,f:()=>b,g:()=>E,i:()=>w,n:()=>L}),r(9456);var i=r(66360),o=r(53648),a=r(15008),n=r(98472),s=r(62484),l=r(74784),c=r(37008),d=r(47364),u=r(82315),h=r(90484),m=r(48880),f=r(20136);const p=v();function v(){return(0,c.Su)()}const g=l.e,_=l.e;function x(e,t){return(0,l.c)(t,e)}function T(e){return e[3]}function E(e){return e}function b(e,t,r,i){return(0,c.WK)(e,t,r,i)}function S(e,t,r){if(null==t)return!1;if(!y(e,t,A))return!1;let{t0:i,t1:o}=A;if((i<0||o<i&&o>0)&&(i=o),i<0)return!1;if(r){const{origin:e,direction:o}=t;r[0]=e[0]+o[0]*i,r[1]=e[1]+o[1]*i,r[2]=e[2]+o[2]*i}return!0}const A={t0:0,t1:0};function y(e,t,r){const{origin:i,direction:o}=t,a=C;a[0]=i[0]-e[0],a[1]=i[1]-e[1],a[2]=i[2]-e[2];const n=o[0]*o[0]+o[1]*o[1]+o[2]*o[2];if(0===n)return!1;const s=2*(o[0]*a[0]+o[1]*a[1]+o[2]*a[2]),l=s*s-4*n*(a[0]*a[0]+a[1]*a[1]+a[2]*a[2]-e[3]*e[3]);if(l<0)return!1;const c=Math.sqrt(l);return r.t0=(-s-c)/(2*n),r.t1=(-s+c)/(2*n),!0}const C=(0,s.Su)();function w(e,t){return S(e,t,null)}function M(e,t,r){const i=f.Wq.get(),o=f.SW.get();(0,n.b)(i,t.origin,t.direction);const s=T(e);(0,n.b)(r,i,t.origin),(0,n.h)(r,r,1/(0,n.l)(r)*s);const l=I(e,t.origin),c=(0,m.i4)(t.origin,r);return(0,a.IT)(o,c+l,i),(0,n.e)(r,r,o),r}function R(e,t,r){const i=(0,n.f)(f.Wq.get(),t,e),o=(0,n.h)(f.Wq.get(),i,e[3]/(0,n.l)(i));return(0,n.g)(r,o,e)}function I(e,t){const r=(0,n.f)(f.Wq.get(),t,e),i=(0,n.l)(r),a=T(e),s=a+Math.abs(a-i);return(0,o.Uj)(a/s)}const O=(0,s.Su)();function N(e,t,r,i){const a=(0,n.f)(O,t,e);switch(r){case u.k.X:{const e=(0,o.Iv)(a,O)[2];return(0,n.s)(i,-Math.sin(e),Math.cos(e),0)}case u.k.Y:{const e=(0,o.Iv)(a,O),t=e[1],r=e[2],s=Math.sin(t);return(0,n.s)(i,-s*Math.cos(r),-s*Math.sin(r),Math.cos(t))}case u.k.Z:return(0,n.n)(i,a);default:return}}function P(e,t){const r=(0,n.f)(D,t,e);return(0,n.l)(r)-e[3]}function L(e,t){const r=(0,n.a)(e,t),i=T(e);return r<=i*i}const D=(0,s.Su)(),F=v();Object.freeze(Object.defineProperty({__proto__:null,NullSphere:p,altitudeAt:P,angleToSilhouette:I,axisAt:N,clear:function(e){e[0]=e[1]=e[2]=e[3]=0},closestPoint:function(e,t,r){return S(e,t,r)?r:((0,h.CE)(t,e,r),R(e,r,r))},closestPointOnSilhouette:M,containsPoint:L,copy:x,create:v,distanceToSilhouette:function(e,t){const r=(0,n.f)(f.Wq.get(),t,e),i=(0,n.p)(r),o=e[3]*e[3];return Math.sqrt(Math.abs(i-o))},elevate:function(e,t,r){return e!==r&&(r[0]=e[0],r[1]=e[1],r[2]=e[2]),r[3]=e[3]+t,r},equals:_,exactEquals:g,fromCenterAndRadius:function(e,t){return(0,c.WK)(e[0],e[1],e[2],t)},fromRadius:function(e,t){return e[0]=e[1]=e[2]=0,e[3]=t,e},fromValues:b,getCenter:E,getRadius:T,intersectLine:function(e,t,r){const i=(0,h.i_)(t,r);if(!y(e,i,A))return[];const{origin:o,direction:a}=i,{t0:l,t1:c}=A,u=t=>{const r=(0,s.Su)();return(0,n.r)(r,o,a,t),R(e,r,r)};return Math.abs(l-c)<(0,d.Au)()?[u(l)]:[u(l),u(c)]},intersectRay:S,intersectRayClosestSilhouette:function(e,t,r){if(S(e,t,r))return r;const i=M(e,t,f.Wq.get());return(0,n.g)(r,t.origin,(0,n.h)(f.Wq.get(),t.direction,(0,n.q)(t.origin,i)/(0,n.l)(t.direction))),r},intersectsRay:w,projectPoint:R,setAltitudeAt:function(e,t,r,i){const o=P(e,t),a=N(e,t,u.k.Z,D),s=(0,n.h)(D,a,r-o);return(0,n.g)(i,t,s)},setExtent:function(e,t,r){return i.c.getLogger("geoscene.geometry.support.sphere").error("sphere.setExtent is not yet supported"),e!==r&&x(e,r),r},tmpSphere:F,union:function(e,t,r=(0,c.Su)()){const i=(0,n.q)(e,t),o=e[3],a=t[3];return i+a<o?((0,l.c)(r,e),r):i+o<a?((0,l.c)(r,t),r):((0,n.m)(r,e,t,(i+a-o)/(2*i)),r[3]=(i+o+a)/2,r)},wrap:function(e){return e}},Symbol.toStringTag,{value:"Module"}))},52180:(e,t,r)=>{r.d(t,{o:()=>o});var i=r(52324);class o{constructor(e){this._allocator=e,this._items=[],this._itemsPtr=0,this._grow()}get(){return 0===this._itemsPtr&&(0,i.o)((()=>this._reset())),this._itemsPtr===this._items.length&&this._grow(),this._items[this._itemsPtr++]}_reset(){const e=Math.min(3*Math.max(8,this._itemsPtr),this._itemsPtr+3*a);this._items.length=Math.min(e,this._items.length),this._itemsPtr=0}_grow(){for(let e=0;e<Math.max(8,Math.min(this._items.length,a));e++)this._items.push(this._allocator())}}const a=1024},95728:(e,t,r)=>{r.d(t,{a:()=>o});var i=r(20744);let o=class e{constructor(e,t,r=""){this.major=e,this.minor=t,this._context=r}lessThan(e,t){return this.major<e||e===this.major&&this.minor<t}since(e,t){return!this.lessThan(e,t)}validate(e){if(this.major!==e.major){const t=this._context&&this._context+":",r=this._context&&this._context+" ";throw new i.c(t+"unsupported-version",`Required major ${r}version is '${this.major}', but got '\${version.major}.\${version.minor}'`,{version:e})}}clone(){return new e(this.major,this.minor,this._context)}static parse(t,r=""){const[o,a]=t.split("."),n=/^\s*\d+\s*$/;if(!o?.match||!n.test(o))throw new i.c((r&&r+":")+"invalid-version","Expected major version to be a number, but got '${version}'",{version:t});if(!a?.match||!n.test(a))throw new i.c((r&&r+":")+"invalid-version","Expected minor version to be a number, but got '${version}'",{version:t});const s=parseInt(o,10),l=parseInt(a,10);return new e(s,l,r)}}},8472:(e,t,r)=>{function i(e){return e=e||globalThis.location.hostname,c.some((t=>null!=e?.match(t)))}function o(e,t){return e&&(t=t||globalThis.location.hostname)?null!=t.match(a)||null!=t.match(s)?e.replace("static.geoscene.cn","staticdev.geoscene.cn"):null!=t.match(n)||null!=t.match(l)?e.replace("static.geoscene.cn","staticqa.geoscene.cn"):e:e}r.d(t,{OM:()=>i,Ur:()=>o});const a=/^devext.geoscene.cn$/,n=/^qaext.geoscene.cn$/,s=/^[\w-]*\.mapsdevext.geoscene.cn$/,l=/^[\w-]*\.mapsqa.geoscene.cn$/,c=[/^([\w-]*\.)?[\w-]*\.zrh-dev-local.geoscene.cn$/,a,n,/^jsapps.geoscene.cn$/,s,l]},38600:(e,t,r)=>{function i(){return new Float32Array(2)}function o(e,t){const r=new Float32Array(2);return r[0]=e,r[1]=t,r}function a(){return i()}function n(){return o(1,1)}function s(){return o(1,0)}function l(){return o(0,1)}r.d(t,{Qw:()=>d,Su:()=>i,WK:()=>o,cX:()=>c});const c=a(),d=n(),u=s(),h=l();Object.freeze(Object.defineProperty({__proto__:null,ONES:d,UNIT_X:u,UNIT_Y:h,ZEROS:c,clone:function(e){const t=new Float32Array(2);return t[0]=e[0],t[1]=e[1],t},create:i,createView:function(e,t){return new Float32Array(e,t,2)},fromValues:o,ones:n,unitX:s,unitY:l,zeros:a},Symbol.toStringTag,{value:"Module"}))},92896:(e,t,r)=>{r.d(t,{mc:()=>o});var i=r(72008);function o(e,t=!1){return e<=i._?t?new Array(e).fill(0):new Array(e):new Float32Array(e)}},90484:(e,t,r)=>{r.d(t,{CE:()=>d,Su:()=>n,em:()=>l,i_:()=>c}),r(92400);var i=r(52180),o=r(98472),a=r(62484);function n(e){return e?s((0,a.ct)(e.origin),(0,a.ct)(e.direction)):s((0,a.Su)(),(0,a.Su)())}function s(e,t){return{origin:e,direction:t}}function l(e,t){const r=u.get();return r.origin=e,r.direction=t,r}function c(e,t,r=n()){return(0,o.c)(r.origin,e),(0,o.f)(r.direction,t,e),r}function d(e,t,r){const i=(0,o.k)(e.direction,(0,o.f)(r,t,e.origin));return(0,o.g)(r,e.origin,(0,o.h)(r,e.direction,i)),r}r(20136);const u=new i.o((()=>n()))},96472:(e,t,r)=>{r.d(t,{fetch:()=>jt});var i=r(8472),o=r(77556),a=r(68612),n=r(15008),s=r(87104),l=r(85256),c=r(98472),d=r(62484),u=r(77048),h=r(92896),m=r(71004),f=r(48952),p=r(92160),v=r(12240),g=r(88340),_=r(74960),x=r(57792),T=r(68268),E=r(2888),b=r(5472),S=r(38600);function A(e){if(null==e)return null;const t=null!=e.offset?e.offset:S.cX,r=null!=e.rotation?e.rotation:0,i=null!=e.scale?e.scale:S.Qw,n=(0,a.WK)(1,0,0,0,1,0,t[0],t[1],1),s=(0,a.WK)(Math.cos(r),-Math.sin(r),0,Math.sin(r),Math.cos(r),0,0,0,1),l=(0,a.WK)(i[0],0,0,0,i[1],0,0,0,1),c=(0,a.Su)();return(0,o.iW)(c,s,l),(0,o.iW)(c,n,c),c}class y{constructor(){this.geometries=new Array,this.materials=new Array,this.textures=new Array}}class C{constructor(e,t,r){this.name=e,this.lodThreshold=t,this.pivotOffset=r,this.stageResources=new y,this.numberOfVertices=0}}var w=r(2600),M=r(40196),R=r(33184),I=r(20744),O=r(66360);class N{constructor(){this._outer=new Map}clear(){this._outer.clear()}get empty(){return 0===this._outer.size}get(e,t){return this._outer.get(e)?.get(t)}set(e,t,r){const i=this._outer.get(e);i?i.set(t,r):this._outer.set(e,new Map([[t,r]]))}delete(e,t){const r=this._outer.get(e);r&&(r.delete(t),0===r.size&&this._outer.delete(e))}forEach(e){this._outer.forEach(((t,r)=>e(t,r)))}}var P=r(10860),L=r(95728),D=r(97143);async function F(e,t){const{data:r}=await(0,w.c)(e,{responseType:"image",...t});return r}var U=r(89560),G=r(12260),B=r(71520),V=r(9456),z=r(77860),Y=r(70680),H=r(72008),W=r(28176),j=r(49628),q=r(24832);let K;var k,$;($=k||(k={}))[$.ETC1_RGB=0]="ETC1_RGB",$[$.ETC2_RGBA=1]="ETC2_RGBA",$[$.BC1_RGB=2]="BC1_RGB",$[$.BC3_RGBA=3]="BC3_RGBA",$[$.BC4_R=4]="BC4_R",$[$.BC5_RG=5]="BC5_RG",$[$.BC7_M6_RGB=6]="BC7_M6_RGB",$[$.BC7_M5_RGBA=7]="BC7_M5_RGBA",$[$.PVRTC1_4_RGB=8]="PVRTC1_4_RGB",$[$.PVRTC1_4_RGBA=9]="PVRTC1_4_RGBA",$[$.ASTC_4x4_RGBA=10]="ASTC_4x4_RGBA",$[$.ATC_RGB=11]="ATC_RGB",$[$.ATC_RGBA=12]="ATC_RGBA",$[$.FXT1_RGB=17]="FXT1_RGB",$[$.PVRTC2_4_RGB=18]="PVRTC2_4_RGB",$[$.PVRTC2_4_RGBA=19]="PVRTC2_4_RGBA",$[$.ETC2_EAC_R11=20]="ETC2_EAC_R11",$[$.ETC2_EAC_RG11=21]="ETC2_EAC_RG11",$[$.RGBA32=13]="RGBA32",$[$.RGB565=14]="RGB565",$[$.BGR565=15]="BGR565",$[$.RGBA4444=16]="RGBA4444";var X=r(77800),J=r(22184),Z=r(97684);let Q=null,ee=null;async function te(){return null==ee&&(ee=function(){if(null==K){const e=e=>(0,q.O)(`geoscene/libs/basisu/${e}`);K=r.e(4824).then(r.bind(r,84824)).then((e=>e.b)).then((({default:t})=>t({locateFile:e}).then((e=>(e.initializeBasis(),delete e.then,e)))))}return K}(),Q=await ee),ee}function re(e,t,r,i,o){const a=(0,Z.O4)(t?X.sr.COMPRESSED_RGBA8_ETC2_EAC:X.sr.COMPRESSED_RGB8_ETC2),n=o&&e>1?(4**e-1)/(3*4**(e-1)):1;return Math.ceil(r*i*a*n)}function ie(e){return e.getNumImages()>=1&&!e.isUASTC()}function oe(e){return e.getFaces()>=1&&e.isETC1S()}function ae(e,t,r,i,o,a,n,s){const{compressedTextureETC:l,compressedTextureS3TC:c}=e.capabilities,[d,u]=l?i?[k.ETC2_RGBA,X.sr.COMPRESSED_RGBA8_ETC2_EAC]:[k.ETC1_RGB,X.sr.COMPRESSED_RGB8_ETC2]:c?i?[k.BC3_RGBA,X.sr.COMPRESSED_RGBA_S3TC_DXT5_EXT]:[k.BC1_RGB,X.sr.COMPRESSED_RGB_S3TC_DXT1_EXT]:[k.RGBA32,X.Id.RGBA],h=t.hasMipmap?r:Math.min(1,r),m=[];for(let e=0;e<h;e++)m.push(new Uint8Array(n(e,d))),s(e,d,m[e]);return t.internalFormat=u,t.hasMipmap=m.length>1,t.samplingMode=t.hasMipmap?X.Ud.LINEAR_MIPMAP_LINEAR:X.Ud.LINEAR,t.width=o,t.height=a,new J.w(e,t,{type:"compressed",levels:m})}var ne=r(58608),se=r(74376);const le=()=>O.c.getLogger("geoscene.views.3d.webgl-engine.lib.DDSUtil");function ce(e){return e.charCodeAt(0)+(e.charCodeAt(1)<<8)+(e.charCodeAt(2)<<16)+(e.charCodeAt(3)<<24)}const de=ce("DXT1"),ue=ce("DXT3"),he=ce("DXT5");function me(e,t,r){if(e instanceof ImageData)return me(fe(e),t,r);const i=document.createElement("canvas");return i.width=t,i.height=r,i.getContext("2d").drawImage(e,0,0,i.width,i.height),i}function fe(e){const t=document.createElement("canvas");t.width=e.width,t.height=e.height;const r=t.getContext("2d");if(null==r)throw new I.c("Failed to create 2d context from HTMLCanvasElement");return r.putImageData(e,0,0),t}var pe,ve,ge=r(90736),_e=r(51576);class xe extends ne.u{get parameters(){return this._parameters}constructor(e,t){super(),this._data=e,this.type=se.C.Texture,this._glTexture=null,this._loadingPromise=null,this._loadingController=null,this.events=new z.c,this._parameters={...Ee,...t},this._startPreload(e)}dispose(){this.unload(),this._data=this.frameUpdate=void 0}_startPreload(e){null!=e&&(e instanceof HTMLVideoElement?(this.frameUpdate=t=>this._frameUpdate(e,t),this._startPreloadVideoElement(e)):e instanceof HTMLImageElement&&this._startPreloadImageElement(e))}_startPreloadVideoElement(e){if(!((0,W.ue)(e.src)||"auto"===e.preload&&e.crossOrigin)){e.preload="auto",e.crossOrigin="anonymous";const t=!e.paused;if(e.src=e.src,t&&e.autoplay){const t=()=>{e.removeEventListener("canplay",t),e.play()};e.addEventListener("canplay",t)}}}_startPreloadImageElement(e){(0,W.o9)(e.src)||(0,W.ue)(e.src)||e.crossOrigin||(e.crossOrigin="anonymous",e.src=e.src)}_createDescriptor(e){const t=new _e.O;return t.wrapMode=this._parameters.wrap??X.Qz.REPEAT,t.flipped=!this._parameters.noUnpackFlip,t.samplingMode=this._parameters.mipmap?X.Ud.LINEAR_MIPMAP_LINEAR:X.Ud.LINEAR,t.hasMipmap=!!this._parameters.mipmap,t.preMultiplyAlpha=!!this._parameters.preMultiplyAlpha,t.maxAnisotropy=this._parameters.maxAnisotropy??(this._parameters.mipmap?e.parameters.maxMaxAnisotropy:1),t}get glTexture(){return this._glTexture}get memoryEstimate(){return this._glTexture?.usedMemory||function(e,t){if(null==e)return 0;if((0,H.uk)(e)||(0,H.K2)(e))return t.encoding===G.yk.KTX2_ENCODING?function(e,t){if(null==Q)return e.byteLength;const r=new Q.KTX2File(new Uint8Array(e)),i=oe(r)?re(r.getLevels(),r.getHasAlpha(),r.getWidth(),r.getHeight(),t):0;return r.close(),r.delete(),i}(e,!!t.mipmap):t.encoding===G.yk.BASIS_ENCODING?function(e,t){if(null==Q)return e.byteLength;const r=new Q.BasisFile(new Uint8Array(e)),i=ie(r)?re(r.getNumLevels(0),r.getHasAlpha(),r.getImageWidth(0,0),r.getImageHeight(0,0),t):0;return r.close(),r.delete(),i}(e,!!t.mipmap):e.byteLength;const{width:r,height:i}=e instanceof Image||e instanceof ImageData||e instanceof HTMLCanvasElement||e instanceof HTMLVideoElement?Te(e):t;return(t.mipmap?4/3:1)*r*i*(t.components||4)||0}(this._data,this._parameters)}load(e){if(this._glTexture)return this._glTexture;if(this._loadingPromise)return this._loadingPromise;const t=this._data;return null==t?(this._glTexture=new J.w(e,this._createDescriptor(e),null),this._glTexture):(this._parameters.reloadable||(this._data=void 0),"string"==typeof t?this._loadFromURL(e,t):t instanceof Image?this._loadFromImageElement(e,t):t instanceof HTMLVideoElement?this._loadFromVideoElement(e,t):t instanceof ImageData||t instanceof HTMLCanvasElement?this._loadFromImage(e,t):((0,H.uk)(t)||(0,H.K2)(t))&&this._parameters.encoding===G.yk.DDS_ENCODING?this._loadFromDDSData(e,t):((0,H.uk)(t)||(0,H.K2)(t))&&this._parameters.encoding===G.yk.KTX2_ENCODING?this._loadFromKTX2(e,t):((0,H.uk)(t)||(0,H.K2)(t))&&this._parameters.encoding===G.yk.BASIS_ENCODING?this._loadFromBasis(e,t):(0,H.K2)(t)?this._loadFromPixelData(e,t):(0,H.uk)(t)?this._loadFromPixelData(e,new Uint8Array(t)):null)}_frameUpdate(e,t){return null==this._glTexture||e.readyState<pe.HAVE_CURRENT_DATA||t===e.currentTime?t:(this._glTexture.setData(e),this._glTexture.descriptor.hasMipmap&&this._glTexture.generateMipmap(),this._parameters.updateCallback&&this._parameters.updateCallback(),e.currentTime)}_loadFromDDSData(e,t){return this._glTexture=function(e,t,r){const i=function(e,t){const r=new Int32Array(e,0,31);if(542327876!==r[0])return le().error("Invalid magic number in DDS header"),null;if(!(4&r[20]))return le().error("Unsupported format, must contain a FourCC code"),null;const i=r[21];let o,a;switch(i){case de:o=8,a=X.sr.COMPRESSED_RGB_S3TC_DXT1_EXT;break;case ue:o=16,a=X.sr.COMPRESSED_RGBA_S3TC_DXT3_EXT;break;case he:o=16,a=X.sr.COMPRESSED_RGBA_S3TC_DXT5_EXT;break;default:return le().error("Unsupported FourCC code:",function(e){return String.fromCharCode(255&e,e>>8&255,e>>16&255,e>>24&255)}(i)),null}let n=1,s=r[4],l=r[3];0==(3&s)&&0==(3&l)||(le().warn("Rounding up compressed texture size to nearest multiple of 4."),s=s+3&-4,l=l+3&-4);const c=s,d=l;let u,h;131072&r[2]&&!1!==t&&(n=Math.max(1,r[7]));let m=r[1]+4;const f=[];for(let t=0;t<n;++t)h=(s+3>>2)*(l+3>>2)*o,u=new Uint8Array(e,m,h),f.push(u),m+=h,s=Math.max(1,s>>1),l=Math.max(1,l>>1);return{textureData:{type:"compressed",levels:f},internalFormat:a,width:c,height:d}}(r,t.hasMipmap??!1);if(null==i)throw new Error("DDS texture data is null");const{textureData:o,internalFormat:a,width:n,height:s}=i;return t.samplingMode=o.levels.length>1?X.Ud.LINEAR_MIPMAP_LINEAR:X.Ud.LINEAR,t.hasMipmap=o.levels.length>1,t.internalFormat=a,t.width=n,t.height=s,new J.w(e,t,o)}(e,this._createDescriptor(e),t),this._glTexture}_loadFromKTX2(e,t){return this._loadAsync((()=>async function(e,t,r){null==Q&&(Q=await te());const i=new Q.KTX2File(new Uint8Array(r));if(!oe(i))return null;i.startTranscoding();const o=ae(e,t,i.getLevels(),i.getHasAlpha(),i.getWidth(),i.getHeight(),((e,t)=>i.getImageTranscodedSizeInBytes(e,0,0,t)),((e,t,r)=>i.transcodeImage(r,e,0,0,t,0,-1,-1)));return i.close(),i.delete(),o}(e,this._createDescriptor(e),t).then((e=>(this._glTexture=e,e)))))}_loadFromBasis(e,t){return this._loadAsync((()=>async function(e,t,r){null==Q&&(Q=await te());const i=new Q.BasisFile(new Uint8Array(r));if(!ie(i))return null;i.startTranscoding();const o=ae(e,t,i.getNumLevels(0),i.getHasAlpha(),i.getImageWidth(0,0),i.getImageHeight(0,0),((e,t)=>i.getImageTranscodedSizeInBytes(0,e,t)),((e,t,r)=>i.transcodeImage(r,0,e,t,0,0)));return i.close(),i.delete(),o}(e,this._createDescriptor(e),t).then((e=>(this._glTexture=e,e)))))}_loadFromPixelData(e,t){(0,ge.Uc)(this._parameters.width>0&&this._parameters.height>0);const r=this._createDescriptor(e);return r.pixelFormat=1===this._parameters.components?X.Id.LUMINANCE:3===this._parameters.components?X.Id.RGB:X.Id.RGBA,r.width=this._parameters.width??0,r.height=this._parameters.height??0,this._glTexture=new J.w(e,r,t),this._glTexture}_loadFromURL(e,t){return this._loadAsync((async r=>{const i=await F(t,{signal:r});return(0,P.wp)(r),this._loadFromImage(e,i)}))}_loadFromImageElement(e,t){return t.complete?this._loadFromImage(e,t):this._loadAsync((async r=>{const i=await(0,j.wx)(t,t.src,!1,r);return(0,P.wp)(r),this._loadFromImage(e,i)}))}_loadFromVideoElement(e,t){return t.readyState>=pe.HAVE_CURRENT_DATA?this._loadFromImage(e,t):this._loadFromVideoElementAsync(e,t)}_loadFromVideoElementAsync(e,t){return this._loadAsync((r=>new Promise(((i,o)=>{const a=()=>{t.removeEventListener("loadeddata",n),t.removeEventListener("error",s),(0,Y.oR)(l)},n=()=>{t.readyState>=pe.HAVE_CURRENT_DATA&&(a(),i(this._loadFromImage(e,t)))},s=e=>{a(),o(e||new I.c("Failed to load video"))};t.addEventListener("loadeddata",n),t.addEventListener("error",s);const l=(0,P.wD)(r,(()=>s((0,P.Uh)())))}))))}_loadFromImage(e,t){let r=t;if(!(r instanceof HTMLVideoElement)){const{maxTextureSize:t}=e.parameters;r=this._parameters.downsampleUncompressed?function(e,t){let r=e.width*e.height;if(r<4096)return e instanceof ImageData?fe(e):e;let i=e.width,o=e.height;do{i=Math.ceil(i/2),o=Math.ceil(o/2),r=i*o}while(r>1048576||null!=t&&(i>t||o>t));return me(e,i,o)}(r,t):function(e,t){const r=Math.max(e.width,e.height);if(r<=t)return e;const i=t/r;return me(e,Math.round(e.width*i),Math.round(e.height*i))}(r,t)}const i=Te(r);this._parameters.width=i.width,this._parameters.height=i.height;const o=this._createDescriptor(e);return o.pixelFormat=3===this._parameters.components?X.Id.RGB:X.Id.RGBA,o.width=i.width,o.height=i.height,this._glTexture=new J.w(e,o,r),this._glTexture}_loadAsync(e){const t=new AbortController;this._loadingController=t;const r=e(t.signal);this._loadingPromise=r;const i=()=>{this._loadingController===t&&(this._loadingController=null),this._loadingPromise===r&&(this._loadingPromise=null)};return r.then(i,i),r}unload(){if(this._glTexture=(0,Y.yI)(this._glTexture),null!=this._loadingController){const e=this._loadingController;this._loadingController=null,this._loadingPromise=null,e.abort()}this.events.emit("unloaded")}}function Te(e){return e instanceof HTMLVideoElement?{width:e.videoWidth,height:e.videoHeight}:e}(ve=pe||(pe={}))[ve.HAVE_NOTHING=0]="HAVE_NOTHING",ve[ve.HAVE_METADATA=1]="HAVE_METADATA",ve[ve.HAVE_CURRENT_DATA=2]="HAVE_CURRENT_DATA",ve[ve.HAVE_FUTURE_DATA=3]="HAVE_FUTURE_DATA",ve[ve.HAVE_ENOUGH_DATA=4]="HAVE_ENOUGH_DATA";const Ee={wrap:{s:X.Qz.REPEAT,t:X.Qz.REPEAT},mipmap:!0,noUnpackFlip:!1,preMultiplyAlpha:!1,downsampleUncompressed:!1};var be=r(4636),Se=r(21288),Ae=r(72364),ye=r(80324),Ce=r(88196),we=r(58472),Me=r(81832),Re=r(10375),Ie=r(81048),Oe=r(56336),Ne=r(81472);const Pe=(0,Ne.ac)(X.w$.SRC_ALPHA,X.w$.ONE,X.w$.ONE_MINUS_SRC_ALPHA,X.w$.ONE_MINUS_SRC_ALPHA),Le=(0,Ne.KO)(X.w$.ONE,X.w$.ONE),De=(0,Ne.KO)(X.w$.ZERO,X.w$.ONE_MINUS_SRC_ALPHA);function Fe(e){return e===Oe.H.FrontFace?null:e===Oe.H.Alpha?De:Le}const Ue={factor:-1,units:-2};function Ge(e,t=X.et.LESS){return e===Oe.H.NONE||e===Oe.H.FrontFace?t:X.et.LEQUAL}var Be=r(40328),Ve=r(84752),ze=r(29244);const Ye=new class{constructor(e=0){this.offset=e,this.sphere=(0,Ve.d)(),this.tmpVertex=(0,d.Su)()}applyToVertex(e,t,r){const i=this.objectTransform.transform,o=(0,c.s)(He,e,t,r),a=(0,c.e)(o,o,i),n=this.offset/(0,c.l)(a);(0,c.r)(a,a,a,n);const s=this.objectTransform.inverse;return(0,c.e)(this.tmpVertex,a,s),this.tmpVertex}applyToMinMax(e,t){const r=this.offset/(0,c.l)(e);(0,c.r)(e,e,e,r);const i=this.offset/(0,c.l)(t);(0,c.r)(t,t,t,i)}applyToAabb(e){const t=this.offset/Math.sqrt(e[0]*e[0]+e[1]*e[1]+e[2]*e[2]);e[0]+=e[0]*t,e[1]+=e[1]*t,e[2]+=e[2]*t;const r=this.offset/Math.sqrt(e[3]*e[3]+e[4]*e[4]+e[5]*e[5]);return e[3]+=e[3]*r,e[4]+=e[4]*r,e[5]+=e[5]*r,e}applyToBoundingSphere(e){const t=(0,c.l)((0,Ve.g)(e)),r=this.offset/t;return(0,c.r)((0,Ve.g)(this.sphere),(0,Ve.g)(e),(0,Ve.g)(e),r),this.sphere[3]=e[3]+e[3]*this.offset/t,this.sphere}};new class{constructor(e=0){this.componentLocalOriginLength=0,this._totalOffset=0,this._offset=0,this._tmpVertex=(0,d.Su)(),this._tmpMbs=(0,Ve.d)(),this._tmpObb=new ze.Eb,this._resetOffset(e)}_resetOffset(e){this._offset=e,this._totalOffset=e}set offset(e){this._resetOffset(e)}get offset(){return this._offset}set componentOffset(e){this._totalOffset=this._offset+e}set localOrigin(e){this.componentLocalOriginLength=(0,c.l)(e)}applyToVertex(e,t,r){const i=(0,c.s)(He,e,t,r),o=(0,c.s)(We,e,t,r+this.componentLocalOriginLength),a=this._totalOffset/(0,c.l)(o);return(0,c.r)(this._tmpVertex,i,o,a),this._tmpVertex}applyToAabb(e){const t=this.componentLocalOriginLength,r=e[0],i=e[1],o=e[2]+t,a=e[3],n=e[4],s=e[5]+t,l=Math.abs(r),c=Math.abs(i),d=Math.abs(o),u=Math.abs(a),h=Math.abs(n),m=Math.abs(s),f=.5*(1+Math.sign(r*a))*Math.min(l,u),p=.5*(1+Math.sign(i*n))*Math.min(c,h),v=.5*(1+Math.sign(o*s))*Math.min(d,m),g=Math.max(l,u),_=Math.max(c,h),x=Math.max(d,m),T=Math.sqrt(f*f+p*p+v*v),E=Math.sign(l+r),b=Math.sign(c+i),S=Math.sign(d+o),A=Math.sign(u+a),y=Math.sign(h+n),C=Math.sign(m+s),w=this._totalOffset;if(T<w)return e[0]-=(1-E)*w,e[1]-=(1-b)*w,e[2]-=(1-S)*w,e[3]+=A*w,e[4]+=y*w,e[5]+=C*w,e;const M=w/Math.sqrt(g*g+_*_+x*x),R=w/T,I=R-M,O=-I;return e[0]+=r*(E*O+R),e[1]+=i*(b*O+R),e[2]+=o*(S*O+R),e[3]+=a*(A*I+M),e[4]+=n*(y*I+M),e[5]+=s*(C*I+M),e}applyToMbs(e){const t=(0,c.l)((0,Ve.g)(e)),r=this._totalOffset/t;return(0,c.r)((0,Ve.g)(this._tmpMbs),(0,Ve.g)(e),(0,Ve.g)(e),r),this._tmpMbs[3]=e[3]+e[3]*this._totalOffset/t,this._tmpMbs}applyToObb(e){return(0,ze.er)(e,this._totalOffset,this._totalOffset,Se.UZ.Global,this._tmpObb),this._tmpObb}},new class{constructor(e=0){this.offset=e,this.tmpVertex=(0,d.Su)()}applyToVertex(e,t,r){const i=(0,c.s)(He,e,t,r),o=(0,c.g)(We,i,this.localOrigin),a=this.offset/(0,c.l)(o);return(0,c.r)(this.tmpVertex,i,o,a),this.tmpVertex}applyToAabb(e){const t=je,r=qe,i=Ke;for(let o=0;o<3;++o)t[o]=e[0+o]+this.localOrigin[o],r[o]=e[3+o]+this.localOrigin[o],i[o]=t[o];const o=this.applyToVertex(t[0],t[1],t[2]);for(let t=0;t<3;++t)e[t]=o[t],e[t+3]=o[t];const a=t=>{const r=this.applyToVertex(t[0],t[1],t[2]);for(let t=0;t<3;++t)e[t]=Math.min(e[t],r[t]),e[t+3]=Math.max(e[t+3],r[t])};for(let e=1;e<8;++e){for(let o=0;o<3;++o)i[o]=0==(e&1<<o)?t[o]:r[o];a(i)}let n=0;for(let e=0;e<3;++e)t[e]*r[e]<0&&(n|=1<<e);if(0!==n&&7!==n)for(let e=0;e<8;++e)if(0==(n&e)){for(let o=0;o<3;++o)i[o]=0!=(n&1<<o)?0:0!=(e&1<<o)?t[o]:r[o];a(i)}for(let t=0;t<3;++t)e[t]-=this.localOrigin[t],e[t+3]-=this.localOrigin[t];return e}};const He=(0,d.Su)(),We=(0,d.Su)(),je=(0,d.Su)(),qe=(0,d.Su)(),Ke=(0,d.Su)();function ke(e,t,r){const{data:i,indices:o}=e,a=t.typedBuffer,n=t.typedBufferStride,s=o.length;r*=n;for(let e=0;e<s;++e){const t=2*o[e];a[r]=i[t],a[r+1]=i[t+1],r+=n}}function $e(e,t,r,i){const{data:o,indices:a}=e,n=t.typedBuffer,s=t.typedBufferStride,l=a.length;if(r*=s,null==i||1===i)for(let e=0;e<l;++e){const t=3*a[e];n[r]=o[t],n[r+1]=o[t+1],n[r+2]=o[t+2],r+=s}else for(let e=0;e<l;++e){const t=3*a[e];for(let e=0;e<i;++e)n[r]=o[t],n[r+1]=o[t+1],n[r+2]=o[t+2],r+=s}}function Xe(e,t,r,i=1){const{data:o,indices:a}=e,n=t.typedBuffer,s=t.typedBufferStride,l=a.length;if(r*=s,1===i)for(let e=0;e<l;++e){const t=4*a[e];n[r]=o[t],n[r+1]=o[t+1],n[r+2]=o[t+2],n[r+3]=o[t+3],r+=s}else for(let e=0;e<l;++e){const t=4*a[e];for(let e=0;e<i;++e)n[r]=o[t],n[r+1]=o[t+1],n[r+2]=o[t+2],n[r+3]=o[t+3],r+=s}}function Je(e,t,r,i,o=1){const a=t.typedBuffer,n=t.typedBufferStride;if(i*=n,1===o)for(let t=0;t<r;++t)a[i]=e[0],a[i+1]=e[1],a[i+2]=e[2],a[i+3]=e[3],i+=n;else for(let t=0;t<r;++t)for(let t=0;t<o;++t)a[i]=e[0],a[i+1]=e[1],a[i+2]=e[2],a[i+3]=e[3],i+=n}function Ze(e,t,r,i,o,a){switch(e){case be.K.POSITION:{(0,ge.Uc)(3===t.size);const i=o.getField(e,m.Yj);(0,ge.Uc)(!!i,`No buffer view for ${e}`),i&&function(e,t,r,i,o=1){if(!t)return void $e(e,r,i,o);const{data:a,indices:s}=e,l=r.typedBuffer,c=r.typedBufferStride,d=s.length,u=t[0],h=t[1],m=t[2],f=t[4],p=t[5],v=t[6],g=t[8],_=t[9],x=t[10],T=t[12],E=t[13],b=t[14];i*=c;let S=0,A=0,y=0;const C=(0,n.yp)(t)?e=>{S=a[e]+T,A=a[e+1]+E,y=a[e+2]+b}:e=>{const t=a[e],r=a[e+1],i=a[e+2];S=u*t+f*r+g*i+T,A=h*t+p*r+_*i+E,y=m*t+v*r+x*i+b};if(1===o)for(let e=0;e<d;++e)C(3*s[e]),l[i]=S,l[i+1]=A,l[i+2]=y,i+=c;else for(let e=0;e<d;++e){C(3*s[e]);for(let e=0;e<o;++e)l[i]=S,l[i+1]=A,l[i+2]=y,i+=c}}(t,r,i,a);break}case be.K.NORMAL:{(0,ge.Uc)(3===t.size);const r=o.getField(e,m.Yj);(0,ge.Uc)(!!r,`No buffer view for ${e}`),r&&function(e,t,r,i,o=1){if(!t)return void $e(e,r,i,o);const{data:a,indices:s}=e,l=t,c=r.typedBuffer,d=r.typedBufferStride,u=s.length,h=l[0],m=l[1],f=l[2],p=l[4],v=l[5],g=l[6],_=l[8],x=l[9],T=l[10],E=!(0,n.QT)(l),b=1e-6,S=1-b;i*=d;let A=0,y=0,C=0;const w=(0,n.yp)(l)?e=>{A=a[e],y=a[e+1],C=a[e+2]}:e=>{const t=a[e],r=a[e+1],i=a[e+2];A=h*t+p*r+_*i,y=m*t+v*r+x*i,C=f*t+g*r+T*i};if(1===o)if(E)for(let e=0;e<u;++e){w(3*s[e]);const t=A*A+y*y+C*C;if(t<S&&t>b){const e=1/Math.sqrt(t);c[i]=A*e,c[i+1]=y*e,c[i+2]=C*e}else c[i]=A,c[i+1]=y,c[i+2]=C;i+=d}else for(let e=0;e<u;++e)w(3*s[e]),c[i]=A,c[i+1]=y,c[i+2]=C,i+=d;else for(let e=0;e<u;++e){if(w(3*s[e]),E){const e=A*A+y*y+C*C;if(e<S&&e>b){const t=1/Math.sqrt(e);A*=t,y*=t,C*=t}}for(let e=0;e<o;++e)c[i]=A,c[i+1]=y,c[i+2]=C,i+=d}}(t,i,r,a);break}case be.K.NORMALCOMPRESSED:{(0,ge.Uc)(2===t.size);const r=o.getField(e,m.iN);(0,ge.Uc)(!!r,`No buffer view for ${e}`),r&&ke(t,r,a);break}case be.K.UV0:{(0,ge.Uc)(2===t.size);const r=o.getField(e,m.U7);(0,ge.Uc)(!!r,`No buffer view for ${e}`),r&&ke(t,r,a);break}case be.K.COLOR:case be.K.SYMBOLCOLOR:{const r=o.getField(e,m.sb);(0,ge.Uc)(!!r,`No buffer view for ${e}`),(0,ge.Uc)(3===t.size||4===t.size),!r||3!==t.size&&4!==t.size||function(e,t,r,i,o=1){const{data:a,indices:n}=e,s=r.typedBuffer,l=r.typedBufferStride,c=n.length;if(i*=l,t!==a.length||4!==t)if(1!==o)if(4!==t)for(let e=0;e<c;++e){const t=3*n[e];for(let e=0;e<o;++e)s[i]=a[t],s[i+1]=a[t+1],s[i+2]=a[t+2],s[i+3]=255,i+=l}else for(let e=0;e<c;++e){const t=4*n[e];for(let e=0;e<o;++e)s[i]=a[t],s[i+1]=a[t+1],s[i+2]=a[t+2],s[i+3]=a[t+3],i+=l}else{if(4===t){for(let e=0;e<c;++e){const t=4*n[e];s[i]=a[t],s[i+1]=a[t+1],s[i+2]=a[t+2],s[i+3]=a[t+3],i+=l}return}for(let e=0;e<c;++e){const t=3*n[e];s[i]=a[t],s[i+1]=a[t+1],s[i+2]=a[t+2],s[i+3]=255,i+=l}}else{s[i]=a[0],s[i+1]=a[1],s[i+2]=a[2],s[i+3]=a[3];const e=new Uint32Array(r.typedBuffer.buffer,r.start),t=l/4,n=e[i/=4];i+=t;const d=c*o;for(let r=1;r<d;++r)e[i]=n,i+=t}}(t,t.size,r,a);break}case be.K.COLORFEATUREATTRIBUTE:{const r=o.getField(e,m.AZ);(0,ge.Uc)(!!r,`No buffer view for ${e}`),(0,ge.Uc)(1===t.size),r&&1===t.size&&function(e,t,r){const{data:i,indices:o}=e,a=t.typedBuffer,n=t.typedBufferStride,s=o.length,l=i[0];r*=n;for(let e=0;e<s;++e)a[r]=l,r+=n}(t,r,a);break}case be.K.TANGENT:{(0,ge.Uc)(4===t.size);const r=o.getField(e,m._5);(0,ge.Uc)(!!r,`No buffer view for ${e}`),r&&function(e,t,r,i,o=1){if(!t)return void Xe(e,r,i,o);const{data:a,indices:s}=e,l=t,c=r.typedBuffer,d=r.typedBufferStride,u=s.length,h=l[0],m=l[1],f=l[2],p=l[4],v=l[5],g=l[6],_=l[8],x=l[9],T=l[10],E=!(0,n.QT)(l),b=1e-6,S=1-b;if(i*=d,1===o)for(let e=0;e<u;++e){const t=4*s[e],r=a[t],o=a[t+1],n=a[t+2],l=a[t+3];let u=h*r+p*o+_*n,A=m*r+v*o+x*n,y=f*r+g*o+T*n;if(E){const e=u*u+A*A+y*y;if(e<S&&e>b){const t=1/Math.sqrt(e);u*=t,A*=t,y*=t}}c[i]=u,c[i+1]=A,c[i+2]=y,c[i+3]=l,i+=d}else for(let e=0;e<u;++e){const t=4*s[e],r=a[t],n=a[t+1],l=a[t+2],u=a[t+3];let A=h*r+p*n+_*l,y=m*r+v*n+x*l,C=f*r+g*n+T*l;if(E){const e=A*A+y*y+C*C;if(e<S&&e>b){const t=1/Math.sqrt(e);A*=t,y*=t,C*=t}}for(let e=0;e<o;++e)c[i]=A,c[i+1]=y,c[i+2]=C,c[i+3]=u,i+=d}}(t,i,r,a);break}case be.K.PROFILERIGHT:case be.K.PROFILEUP:case be.K.PROFILEVERTEXANDNORMAL:case be.K.FEATUREVALUE:{(0,ge.Uc)(4===t.size);const r=o.getField(e,m._5);(0,ge.Uc)(!!r,`No buffer view for ${e}`),r&&Xe(t,r,a)}}}class Qe{constructor(e){this.vertexBufferLayout=e}elementCount(e){return e.attributes.get(be.K.POSITION).indices.length}write(e,t,r,i,o){!function(e,t,r,i,o,a){for(const n of t.fields.keys()){const t=e.attributes.get(n),s=t?.indices;if(t&&s)Ze(n,t,r,i,o,a);else if(n===be.K.OBJECTANDLAYERIDCOLOR&&null!=e.objectAndLayerIdColor){const t=e.attributes.get(be.K.POSITION)?.indices;if(t){const r=t.length,i=o.getField(n,m.sb);Je(e.objectAndLayerIdColor,i,r,a)}}}}(r,this.vertexBufferLayout,e,t,i,o)}}var et=r(22592),tt=r(37008),rt=r(55164),it=r(60072),ot=r(74072),at=r(74780),nt=r(93316),st=r(63524),lt=r(1492);X.et.LESS,X.et.ALWAYS;const ct={mask:255},dt={function:{func:X.et.ALWAYS,ref:G.Aj.OutlineVisualElementMask,mask:G.Aj.OutlineVisualElementMask},operation:{fail:X.QD.KEEP,zFail:X.QD.KEEP,zPass:X.QD.ZERO}},ut={function:{func:X.et.ALWAYS,ref:G.Aj.OutlineVisualElementMask,mask:G.Aj.OutlineVisualElementMask},operation:{fail:X.QD.KEEP,zFail:X.QD.KEEP,zPass:X.QD.REPLACE}};X.et.EQUAL,G.Aj.OutlineVisualElementMask,G.Aj.OutlineVisualElementMask,X.QD.KEEP,X.QD.KEEP,X.QD.KEEP,X.et.NOTEQUAL,G.Aj.OutlineVisualElementMask,G.Aj.OutlineVisualElementMask,X.QD.KEEP,X.QD.KEEP,X.QD.KEEP;const ht=[1,1,.5],mt=[0,.6,.2],ft=[0,1,.2];var pt=r(83840);class vt extends it.Yj{constructor(){super(...arguments),this.isSchematic=!1,this.usePBR=!1,this.mrrFactors=(0,d.eu)(ht),this.hasVertexColors=!1,this.hasSymbolColors=!1,this.doubleSided=!1,this.doubleSidedType="normal",this.cullFace=G.CM.Back,this.isInstanced=!1,this.hasInstancedColor=!1,this.emissiveFactor=(0,d.WK)(0,0,0),this.instancedDoublePrecision=!1,this.normalType=Ce.g.Attribute,this.receiveShadows=!0,this.receiveAmbientOcclusion=!0,this.castShadows=!0,this.shadowMappingEnabled=!1,this.ambient=(0,d.WK)(.2,.2,.2),this.diffuse=(0,d.WK)(.8,.8,.8),this.externalColor=(0,tt.WK)(1,1,1,1),this.colorMixMode="multiply",this.opacity=1,this.layerOpacity=1,this.origin=(0,d.Su)(),this.hasSlicePlane=!1,this.hasSliceHighlight=!0,this.offsetTransparentBackfaces=!1,this.vvSize=null,this.vvColor=null,this.vvOpacity=null,this.vvSymbolAnchor=null,this.vvSymbolRotationMatrix=null,this.modelTransformation=null,this.transparent=!1,this.writeDepth=!0,this.customDepthTest=G.sr.Less,this.textureAlphaMode=G.OA.Blend,this.textureAlphaCutoff=ot.k,this.textureAlphaPremultiplied=!1,this.hasOccludees=!1,this.renderOccluded=Ie.C2.Occlude,this.isDecoration=!1}}it.yO;class gt extends nt.O{initializeConfiguration(e,t){t.spherical=e.viewingMode===Se.UZ.Global,t.doublePrecisionRequiresObfuscation=e.rctx.driverTest.doublePrecisionRequiresObfuscation.result,t.textureCoordinateType=t.hasColorTexture||t.hasMetallicRoughnessTexture||t.hasEmissionTexture||t.hasOcclusionTexture||t.hasNormalTexture?rt.i.Default:rt.i.None,t.objectAndLayerIdColorInstanced=t.instanced}initializeProgram(e){return this._initializeProgram(e,gt.shader)}_initializeProgram(e,t){return new lt.G(e.rctx,t.get().build(this.configuration),st.q)}_convertDepthTestFunction(e){return e===G.sr.Lequal?X.et.LEQUAL:X.et.LESS}_makePipeline(e,t){const r=this.configuration,i=e===Oe.H.NONE,o=e===Oe.H.FrontFace;return(0,Ne._w)({blending:r.output!==ye.mC.Color&&r.output!==ye.mC.Alpha||!r.transparent?null:i?Pe:Fe(e),culling:_t(r)?(0,Ne.AD)(r.cullFace):null,depthTest:{func:Ge(e,this._convertDepthTestFunction(r.customDepthTest))},depthWrite:(i||o)&&r.writeDepth?Ne.AT:null,colorWrite:Ne.kp,stencilWrite:r.hasOccludees?ct:null,stencilTest:r.hasOccludees?t?ut:dt:null,polygonOffset:i||o?null:(a=r.enableOffset,a?Ue:null)});var a}initializePipeline(){return this._occludeePipelineState=this._makePipeline(this.configuration.transparencyPassType,!0),this._makePipeline(this.configuration.transparencyPassType,!1)}getPipeline(e){return e?this._occludeePipelineState:super.getPipeline()}}function _t(e){return e.cullFace!==G.CM.None||!e.hasSlicePlane&&!e.transparent&&!e.doubleSidedMode}gt.shader=new at.O(pt.D,(()=>r.e(5880).then(r.bind(r,75880))));var xt=r(41948),Tt=r(74912),Et=r(11925),bt=r(75040);class St extends Et.i0{}(0,xt._)([(0,Tt.y)({constValue:!0})],St.prototype,"hasSliceHighlight",void 0),(0,xt._)([(0,Tt.y)({constValue:!1})],St.prototype,"hasSliceInVertexProgram",void 0),(0,xt._)([(0,Tt.y)({constValue:bt.u.Pass})],St.prototype,"pbrTextureBindType",void 0);class At extends St{constructor(){super(...arguments),this.output=ye.mC.Color,this.alphaDiscardMode=G.OA.Opaque,this.doubleSidedMode=we.J.None,this.pbrMode=Me.Ax.Disabled,this.cullFace=G.CM.None,this.transparencyPassType=Oe.H.NONE,this.normalType=Ce.g.Attribute,this.textureCoordinateType=rt.i.None,this.customDepthTest=G.sr.Less,this.spherical=!1,this.hasVertexColors=!1,this.hasSymbolColors=!1,this.hasVerticalOffset=!1,this.hasSlicePlane=!1,this.hasSliceHighlight=!0,this.hasColorTexture=!1,this.hasMetallicRoughnessTexture=!1,this.hasEmissionTexture=!1,this.hasOcclusionTexture=!1,this.hasNormalTexture=!1,this.hasScreenSizePerspective=!1,this.hasVertexTangents=!1,this.hasOccludees=!1,this.multipassEnabled=!1,this.hasModelTransformation=!1,this.offsetBackfaces=!1,this.vvSize=!1,this.vvColor=!1,this.receiveShadows=!1,this.receiveAmbientOcclusion=!1,this.textureAlphaPremultiplied=!1,this.instanced=!1,this.instancedColor=!1,this.objectAndLayerIdColorInstanced=!1,this.instancedDoublePrecision=!1,this.doublePrecisionRequiresObfuscation=!1,this.writeDepth=!0,this.transparent=!1,this.enableOffset=!0,this.cullAboveGround=!1,this.snowCover=!1,this.hasColorTextureTransform=!1,this.hasEmissionTextureTransform=!1,this.hasNormalTextureTransform=!1,this.hasOcclusionTextureTransform=!1,this.hasMetallicRoughnessTextureTransform=!1}}(0,xt._)([(0,Tt.y)({count:ye.mC.COUNT})],At.prototype,"output",void 0),(0,xt._)([(0,Tt.y)({count:G.OA.COUNT})],At.prototype,"alphaDiscardMode",void 0),(0,xt._)([(0,Tt.y)({count:we.J.COUNT})],At.prototype,"doubleSidedMode",void 0),(0,xt._)([(0,Tt.y)({count:Me.Ax.COUNT})],At.prototype,"pbrMode",void 0),(0,xt._)([(0,Tt.y)({count:G.CM.COUNT})],At.prototype,"cullFace",void 0),(0,xt._)([(0,Tt.y)({count:Oe.H.COUNT})],At.prototype,"transparencyPassType",void 0),(0,xt._)([(0,Tt.y)({count:Ce.g.COUNT})],At.prototype,"normalType",void 0),(0,xt._)([(0,Tt.y)({count:rt.i.COUNT})],At.prototype,"textureCoordinateType",void 0),(0,xt._)([(0,Tt.y)({count:G.sr.COUNT})],At.prototype,"customDepthTest",void 0),(0,xt._)([(0,Tt.y)()],At.prototype,"spherical",void 0),(0,xt._)([(0,Tt.y)()],At.prototype,"hasVertexColors",void 0),(0,xt._)([(0,Tt.y)()],At.prototype,"hasSymbolColors",void 0),(0,xt._)([(0,Tt.y)()],At.prototype,"hasVerticalOffset",void 0),(0,xt._)([(0,Tt.y)()],At.prototype,"hasSlicePlane",void 0),(0,xt._)([(0,Tt.y)()],At.prototype,"hasSliceHighlight",void 0),(0,xt._)([(0,Tt.y)()],At.prototype,"hasColorTexture",void 0),(0,xt._)([(0,Tt.y)()],At.prototype,"hasMetallicRoughnessTexture",void 0),(0,xt._)([(0,Tt.y)()],At.prototype,"hasEmissionTexture",void 0),(0,xt._)([(0,Tt.y)()],At.prototype,"hasOcclusionTexture",void 0),(0,xt._)([(0,Tt.y)()],At.prototype,"hasNormalTexture",void 0),(0,xt._)([(0,Tt.y)()],At.prototype,"hasScreenSizePerspective",void 0),(0,xt._)([(0,Tt.y)()],At.prototype,"hasVertexTangents",void 0),(0,xt._)([(0,Tt.y)()],At.prototype,"hasOccludees",void 0),(0,xt._)([(0,Tt.y)()],At.prototype,"multipassEnabled",void 0),(0,xt._)([(0,Tt.y)()],At.prototype,"hasModelTransformation",void 0),(0,xt._)([(0,Tt.y)()],At.prototype,"offsetBackfaces",void 0),(0,xt._)([(0,Tt.y)()],At.prototype,"vvSize",void 0),(0,xt._)([(0,Tt.y)()],At.prototype,"vvColor",void 0),(0,xt._)([(0,Tt.y)()],At.prototype,"receiveShadows",void 0),(0,xt._)([(0,Tt.y)()],At.prototype,"receiveAmbientOcclusion",void 0),(0,xt._)([(0,Tt.y)()],At.prototype,"textureAlphaPremultiplied",void 0),(0,xt._)([(0,Tt.y)()],At.prototype,"instanced",void 0),(0,xt._)([(0,Tt.y)()],At.prototype,"instancedColor",void 0),(0,xt._)([(0,Tt.y)()],At.prototype,"objectAndLayerIdColorInstanced",void 0),(0,xt._)([(0,Tt.y)()],At.prototype,"instancedDoublePrecision",void 0),(0,xt._)([(0,Tt.y)()],At.prototype,"doublePrecisionRequiresObfuscation",void 0),(0,xt._)([(0,Tt.y)()],At.prototype,"writeDepth",void 0),(0,xt._)([(0,Tt.y)()],At.prototype,"transparent",void 0),(0,xt._)([(0,Tt.y)()],At.prototype,"enableOffset",void 0),(0,xt._)([(0,Tt.y)()],At.prototype,"cullAboveGround",void 0),(0,xt._)([(0,Tt.y)()],At.prototype,"snowCover",void 0),(0,xt._)([(0,Tt.y)()],At.prototype,"hasColorTextureTransform",void 0),(0,xt._)([(0,Tt.y)()],At.prototype,"hasEmissionTextureTransform",void 0),(0,xt._)([(0,Tt.y)()],At.prototype,"hasNormalTextureTransform",void 0),(0,xt._)([(0,Tt.y)()],At.prototype,"hasOcclusionTextureTransform",void 0),(0,xt._)([(0,Tt.y)()],At.prototype,"hasMetallicRoughnessTextureTransform",void 0),(0,xt._)([(0,Tt.y)({constValue:!1})],At.prototype,"occlusionPass",void 0),(0,xt._)([(0,Tt.y)({constValue:!0})],At.prototype,"hasVvInstancing",void 0),(0,xt._)([(0,Tt.y)({constValue:!1})],At.prototype,"useCustomDTRExponentForWater",void 0),(0,xt._)([(0,Tt.y)({constValue:!1})],At.prototype,"supportsTextureAtlas",void 0),(0,xt._)([(0,Tt.y)({constValue:!0})],At.prototype,"useFillLights",void 0);var yt=r(59888);class Ct extends gt{initializeConfiguration(e,t){super.initializeConfiguration(e,t),t.hasMetallicRoughnessTexture=!1,t.hasEmissionTexture=!1,t.hasOcclusionTexture=!1,t.hasNormalTexture=!1,t.hasModelTransformation=!1,t.normalType=Ce.g.Attribute,t.doubleSidedMode=we.J.WindingOrder,t.hasVertexTangents=!1}initializeProgram(e){return this._initializeProgram(e,Ct.shader)}}Ct.shader=new at.O(yt.R,(()=>r.e(1980).then(r.bind(r,81980))));class wt extends Ie.iK{constructor(e){super(e,Rt),this.supportsEdges=!0,this.produces=new Map([[Be.y.OPAQUE_MATERIAL,e=>((0,ye.ue)(e)||(0,ye.KQ)(e))&&!this.parameters.transparent],[Be.y.TRANSPARENT_MATERIAL,e=>((0,ye.ue)(e)||(0,ye.KQ)(e))&&this.parameters.transparent&&this.parameters.writeDepth],[Be.y.TRANSPARENT_DEPTH_WRITE_DISABLED_MATERIAL,e=>((0,ye.ue)(e)||(0,ye.KQ)(e))&&this.parameters.transparent&&!this.parameters.writeDepth]]),this._configuration=new At,this._vertexBufferLayout=function(e){const t=(0,Ae.iC)().vec3f(be.K.POSITION);return e.normalType===Ce.g.Compressed?t.vec2i16(be.K.NORMALCOMPRESSED,{glNormalized:!0}):t.vec3f(be.K.NORMAL),e.hasVertexTangents&&t.vec4f(be.K.TANGENT),(e.textureId||e.normalTextureId||e.metallicRoughnessTextureId||e.emissiveTextureId||e.occlusionTextureId)&&t.vec2f(be.K.UV0),e.hasVertexColors&&t.vec4u8(be.K.COLOR),e.hasSymbolColors&&t.vec4u8(be.K.SYMBOLCOLOR),(0,V.c)("enable-feature:objectAndLayerId-rendering")&&t.vec4u8(be.K.OBJECTANDLAYERIDCOLOR),t}(this.parameters)}isVisibleForOutput(e){return e!==ye.mC.Shadow&&e!==ye.mC.ShadowExcludeHighlight&&e!==ye.mC.ShadowHighlight||this.parameters.castShadows}isVisible(){const e=this.parameters;if(!super.isVisible()||0===e.layerOpacity)return!1;const{hasInstancedColor:t,hasVertexColors:r,hasSymbolColors:i,vvColor:o}=e,a="replace"===e.colorMixMode,n=e.opacity>0,s=e.externalColor&&e.externalColor[3]>0,l=t||o||i;return r&&l?a||n:r?a?s:n:l?a||n:a?s:n}getConfiguration(e,t){return this._configuration.output=e,this._configuration.hasNormalTexture=!!this.parameters.normalTextureId,this._configuration.hasColorTexture=!!this.parameters.textureId,this._configuration.hasVertexTangents=this.parameters.hasVertexTangents,this._configuration.instanced=this.parameters.isInstanced,this._configuration.instancedDoublePrecision=this.parameters.instancedDoublePrecision,this._configuration.vvSize=!!this.parameters.vvSize,this._configuration.hasVerticalOffset=null!=this.parameters.verticalOffset,this._configuration.hasScreenSizePerspective=null!=this.parameters.screenSizePerspective,this._configuration.hasSlicePlane=this.parameters.hasSlicePlane,this._configuration.hasSliceHighlight=this.parameters.hasSliceHighlight,this._configuration.alphaDiscardMode=this.parameters.textureAlphaMode,this._configuration.normalType=this.parameters.normalType,this._configuration.transparent=this.parameters.transparent,this._configuration.writeDepth=this.parameters.writeDepth,null!=this.parameters.customDepthTest&&(this._configuration.customDepthTest=this.parameters.customDepthTest),this._configuration.hasOccludees=this.parameters.hasOccludees,this._configuration.cullFace=this.parameters.hasSlicePlane?G.CM.None:this.parameters.cullFace,this._configuration.multipassEnabled=t.multipassEnabled,this._configuration.cullAboveGround=t.multipassTerrain.cullAboveGround,this._configuration.hasModelTransformation=null!=this.parameters.modelTransformation,e!==ye.mC.Color&&e!==ye.mC.Alpha||(this._configuration.hasVertexColors=this.parameters.hasVertexColors,this._configuration.hasSymbolColors=this.parameters.hasSymbolColors,this.parameters.treeRendering?this._configuration.doubleSidedMode=we.J.WindingOrder:this._configuration.doubleSidedMode=this.parameters.doubleSided&&"normal"===this.parameters.doubleSidedType?we.J.View:this.parameters.doubleSided&&"winding-order"===this.parameters.doubleSidedType?we.J.WindingOrder:we.J.None,this._configuration.instancedColor=this.parameters.hasInstancedColor,this._configuration.receiveShadows=this.parameters.receiveShadows&&this.parameters.shadowMappingEnabled,this._configuration.receiveAmbientOcclusion=this.parameters.receiveAmbientOcclusion&&null!=t.ssao,this._configuration.vvColor=!!this.parameters.vvColor,this._configuration.textureAlphaPremultiplied=!!this.parameters.textureAlphaPremultiplied,this._configuration.pbrMode=this.parameters.usePBR?this.parameters.isSchematic?Me.Ax.Schematic:Me.Ax.Normal:Me.Ax.Disabled,this._configuration.hasMetallicRoughnessTexture=!!this.parameters.metallicRoughnessTextureId,this._configuration.hasEmissionTexture=!!this.parameters.emissiveTextureId,this._configuration.hasOcclusionTexture=!!this.parameters.occlusionTextureId,this._configuration.offsetBackfaces=!(!this.parameters.transparent||!this.parameters.offsetTransparentBackfaces),this._configuration.transparencyPassType=t.transparencyPassType,this._configuration.enableOffset=t.camera.relativeElevation<5e5,this._configuration.snowCover=this.hasSnowCover(t),this._configuration.hasColorTextureTransform=!!this.parameters.colorTextureTransformMatrix,this._configuration.hasNormalTextureTransform=!!this.parameters.normalTextureTransformMatrix,this._configuration.hasEmissionTextureTransform=!!this.parameters.emissiveTextureTransformMatrix,this._configuration.hasOcclusionTextureTransform=!!this.parameters.occlusionTextureTransformMatrix,this._configuration.hasMetallicRoughnessTextureTransform=!!this.parameters.metallicRoughnessTextureTransformMatrix),this._configuration}hasSnowCover(e){return null!=e.weather&&e.weatherVisible&&"snowy"===e.weather.type&&"enabled"===e.weather.snowCover}intersect(e,t,r,i,o,a){if(null!=this.parameters.verticalOffset){const e=r.camera;(0,c.s)(Dt,t[12],t[13],t[14]);let a=null;switch(r.viewingMode){case Se.UZ.Global:a=(0,c.n)(Pt,Dt);break;case Se.UZ.Local:a=(0,c.c)(Pt,Nt)}let n=0;const s=(0,c.f)(Ft,Dt,e.eye),l=(0,c.l)(s),d=(0,c.h)(s,s,1/l);let u=null;this.parameters.screenSizePerspective&&(u=(0,c.k)(a,d)),n+=(0,et.u6)(e,l,this.parameters.verticalOffset,u??0,this.parameters.screenSizePerspective),(0,c.h)(a,a,n),(0,c.t)(Lt,a,r.transform.inverseRotation),i=(0,c.f)(It,i,Lt),o=(0,c.f)(Ot,o,Lt)}var n;(0,et.Ep)(e,r,i,o,null!=(n=r.verticalOffset)?(Ye.offset=n,Ye):null,a)}createGLMaterial(e){return new Mt(e)}createBufferWriter(){return new Qe(this._vertexBufferLayout)}}class Mt extends Re.Q{constructor(e){super({...e,...e.material.parameters})}_updateShadowState(e){e.shadowMap.enabled!==this._material.parameters.shadowMappingEnabled&&this._material.setParameters({shadowMappingEnabled:e.shadowMap.enabled})}_updateOccludeeState(e){e.hasOccludees!==this._material.parameters.hasOccludees&&this._material.setParameters({hasOccludees:e.hasOccludees})}beginSlot(e){this._output!==ye.mC.Color&&this._output!==ye.mC.Alpha||(this._updateShadowState(e),this._updateOccludeeState(e));const t=this._material.parameters;this.updateTexture(t.textureId);const r=e.camera.viewInverseTransposeMatrix;return(0,c.s)(t.origin,r[3],r[7],r[11]),this._material.setParameters(this.textureBindParameters),this.ensureTechnique(t.treeRendering?Ct:gt,e)}}const Rt=new class extends vt{constructor(){super(...arguments),this.initTextureTransparent=!1,this.treeRendering=!1,this.hasVertexTangents=!1}},It=(0,d.Su)(),Ot=(0,d.Su)(),Nt=(0,d.WK)(0,0,1),Pt=(0,d.Su)(),Lt=(0,d.Su)(),Dt=(0,d.Su)(),Ft=(0,d.Su)(),Ut=()=>O.c.getLogger("geoscene.views.3d.layers.graphics.objectResourceUtils");function Gt(e){throw new I.c("",`Request for object resource failed: ${e}`)}function Bt(e){const t=e.params,r=t.topology;let i=!0;switch(t.vertexAttributes||(Ut().warn("Geometry must specify vertex attributes"),i=!1),t.topology){case"PerAttributeArray":break;case"Indexed":case null:case void 0:{const e=t.faces;if(e){if(t.vertexAttributes)for(const r in t.vertexAttributes){const t=e[r];t?.values?(null!=t.valueType&&"UInt32"!==t.valueType&&(Ut().warn(`Unsupported indexed geometry indices type '${t.valueType}', only UInt32 is currently supported`),i=!1),null!=t.valuesPerElement&&1!==t.valuesPerElement&&(Ut().warn(`Unsupported indexed geometry values per element '${t.valuesPerElement}', only 1 is currently supported`),i=!1)):(Ut().warn(`Indexed geometry does not specify face indices for '${r}' attribute`),i=!1)}}else Ut().warn("Indexed geometries must specify faces"),i=!1;break}default:Ut().warn(`Unsupported topology '${r}'`),i=!1}e.params.material||(Ut().warn("Geometry requires material"),i=!1);const o=e.params.vertexAttributes;for(const e in o)o[e].values||(Ut().warn("Geometries with externally defined attributes are not yet supported"),i=!1);return i}function Vt(e){const t=(0,u.FZ)();return e.forEach((e=>{const r=e.boundingInfo;null!=r&&((0,u.K8)(t,r.bbMin),(0,u.K8)(t,r.bbMax))})),t}function zt(e){switch(e){case"mask":return G.OA.Mask;case"maskAndTransparency":return G.OA.MaskBlend;case"none":return G.OA.Opaque;default:return G.OA.Blend}}function Yt(e){const t=e.params;return{id:1,material:t.material,texture:t.texture,region:t.texture}}const Ht=new L.a(1,2,"wosr");var Wt=r(44875);async function jt(e,t){const r=function(e){const t=e.match(/(.*\.(gltf|glb))(\?lod=([0-9]+))?$/);return t?{fileType:"gltf",url:t[1],specifiedLodIndex:null!=t[4]?Number(t[4]):null}:e.match(/(.*\.(json|json\.gz))$/)?{fileType:"wosr",url:e,specifiedLodIndex:null}:{fileType:"unknown",url:e,specifiedLodIndex:null}}((0,i.Ur)(e));if("wosr"===r.fileType){const e=await(t.cache?t.cache.loadWOSR(r.url,t):async function(e,t){const r=await async function(e,t){const r=t?.streamDataRequester;if(r)return async function(e,t,r){const i=await(0,M.aG)(t.request(e,"json",r));if(!0===i.ok)return i.value;(0,P.CE)(i.error),Gt(i.error.details.url)}(e,r,t);const i=await(0,M.aG)((0,w.c)(e,t));if(!0===i.ok)return i.value.data;(0,P.CE)(i.error),Gt(i.error)}(e,t),i=await async function(e,t){const r=new Array;for(const i in e){const o=e[i],a=o.images[0].data;if(!a){Ut().warn("Externally referenced texture data is not yet supported");continue}const n=o.encoding+";base64,"+a,s="/textureDefinitions/"+i,l="rgba"===o.channels?o.alphaChannelUsage||"transparency":"none",c={noUnpackFlip:!0,wrap:{s:X.Qz.REPEAT,t:X.Qz.REPEAT},preMultiplyAlpha:zt(l)!==G.OA.Opaque},d=null!=t&&t.disableTextures?Promise.resolve(null):F(n,t);r.push(d.then((e=>({refId:s,image:e,parameters:c,alphaChannelUsage:l}))))}const i=await Promise.all(r),o={};for(const e of i)o[e.refId]=e;return o}(r.textureDefinitions??{},t);let o=0;for(const e in i)if(i.hasOwnProperty(e)){const t=i[e];o+=t?.image?t.image.width*t.image.height*4:0}return{resource:r,textures:i,size:o+(0,R.UN)(r)}}(r.url,t)),{engineResources:i,referenceBoundingBox:o}=function(e,t){const r=new Array,i=new Array,o=new Array,a=new N,n=e.resource,s=L.a.parse(n.version||"1.0","wosr");Ht.validate(s);const l=n.model.name,c=n.model.geometries,u=n.materialDefinitions??{},h=e.textures;let m=0;const f=new Map;for(let e=0;e<c.length;e++){const n=c[e];if(!Bt(n))continue;const s=Yt(n),l=n.params.vertexAttributes,p=[],v=e=>{if("PerAttributeArray"===n.params.topology)return null;const t=n.params.faces;for(const r in t)if(r===e)return t[r].values;return null},g=l[be.K.POSITION],_=g.values.length/g.valuesPerElement;for(const e in l){const t=l[e],r=t.values,i=v(e)??(0,D.yh)(_);p.push([e,new U.O(r,i,t.valuesPerElement,!0)])}const x=s.texture,T=h&&h[x];if(T&&!f.has(x)){const{image:e,parameters:t}=T,r=new xe(e,t);i.push(r),f.set(x,r)}const E=f.get(x),b=E?E.id:void 0,S=s.material;let A=a.get(S,x);if(null==A){const e=u[S.substring(S.lastIndexOf("/")+1)].params;1===e.transparency&&(e.transparency=0);const r=T&&T.alphaChannelUsage,i=e.transparency>0||"transparency"===r||"maskAndTransparency"===r,o=T?zt(T.alphaChannelUsage):void 0,n={ambient:(0,d.eu)(e.diffuse),diffuse:(0,d.eu)(e.diffuse),opacity:1-(e.transparency||0),transparent:i,textureAlphaMode:o,textureAlphaCutoff:.33,textureId:b,initTextureTransparent:!0,doubleSided:!0,cullFace:G.CM.None,colorMixMode:e.externalColorMixMode||"tint",textureAlphaPremultiplied:T?.parameters.preMultiplyAlpha??!1};t?.materialParameters&&Object.assign(n,t.materialParameters),A=new wt(n),a.set(S,x,A)}o.push(A);const y=new B.K(A,p);m+=p.find((e=>e[0]===be.K.POSITION))?.[1]?.indices.length??0,r.push(y)}return{engineResources:[{name:l,stageResources:{textures:i,materials:o,geometries:r},pivotOffset:n.model.pivotOffset,numberOfVertices:m,lodThreshold:null}],referenceBoundingBox:Vt(r)}}(e,t);return{lods:i,referenceBoundingBox:o,isEsriSymbolResource:!1,isWosr:!0}}const a=await(t.cache?t.cache.loadGLTF(r.url,t,!!t.usePBR):(0,T.G)(new x.k(t.streamDataRequester),r.url,t,t.usePBR)),S=a.model.meta?.ESRI_proxyEllipsoid,y=a.meta.isEsriSymbolResource&&null!=S&&"EsriRealisticTreesStyle"===a.meta.ESRI_webstyle;y&&!a.customMeta.geosceneTreeRendering&&(a.customMeta.geosceneTreeRendering=!0,function(e,t){for(let r=0;r<e.model.lods.length;++r){const i=e.model.lods[r];for(const o of i.parts){const i=o.attributes.normal;if(null==i)return;const a=o.attributes.position,l=a.count,u=(0,d.Su)(),h=(0,d.Su)(),f=(0,d.Su)(),p=new Uint8Array(4*l),v=new Float64Array(3*l),g=(0,n.S_)((0,s.Su)(),o.transform);let _=0,x=0;for(let n=0;n<l;n++){a.getVec(n,h),i.getVec(n,u),(0,c.e)(h,h,o.transform),(0,c.f)(f,h,t.center),(0,c.H)(f,f,t.radius);const s=f[2],l=(0,c.l)(f),d=Math.min(.45+.55*l*l,1);(0,c.H)(f,f,t.radius),null!==g&&(0,c.e)(f,f,g),(0,c.n)(f,f),r+1!==e.model.lods.length&&e.model.lods.length>1&&(0,c.m)(f,f,u,s>-1?.2:Math.min(-4*s-3.8,1)),v[_]=f[0],v[_+1]=f[1],v[_+2]=f[2],_+=3,p[x]=255*d,p[x+1]=255*d,p[x+2]=255*d,p[x+3]=255,x+=4}o.attributes.normal=new m.Yj(v),o.attributes.color=new m.sb(p)}}}(a,S));const I=!!t.usePBR,O=a.meta.isEsriSymbolResource?{usePBR:I,isSchematic:!1,treeRendering:y,mrrFactors:[...ft]}:{usePBR:I,isSchematic:!1,treeRendering:!1,mrrFactors:[...ht]},V={...t.materialParameters,treeRendering:y},{engineResources:z,referenceBoundingBox:Y}=function(e,t,r,i){const a=e.model,n=new Array,s=new Map,x=new Map,T=a.lods.length,S=(0,u.FZ)();return a.lods.forEach(((e,y)=>{const w=!0===i.skipHighLods&&(T>1&&0===y||T>3&&1===y)||!1===i.skipHighLods&&null!=i.singleLodIndex&&y!==i.singleLodIndex;if(w&&0!==y)return;const M=new C(e.name,e.lodThreshold,[0,0,0]);e.parts.forEach((e=>{const i=w?new wt({}):function(e,t,r,i,o,a,n){const s=t.material+(t.attributes.normal?"_normal":"")+(t.attributes.color?"_color":"")+(t.attributes.texCoord0?"_texCoord0":"")+(t.attributes.tangent?"_tangent":""),u=e.materials.get(t.material),h=null!=t.attributes.texCoord0,m=null!=t.attributes.normal;if(null==u)return null;const f=function(e){switch(e){case"BLEND":return G.OA.Blend;case"MASK":return G.OA.Mask;case"OPAQUE":case null:case void 0:return G.OA.Opaque}}(u.alphaMode);if(!a.has(s)){if(h){const t=(t,r=!1)=>{if(null!=t&&!n.has(t)){const i=e.textures.get(t);if(null!=i){const e=i.data;n.set(t,new xe((0,b.e6)(e)?e.data:e,{...i.parameters,preMultiplyAlpha:!(0,b.e6)(e)&&r,encoding:(0,b.e6)(e)&&null!=e.encoding?e.encoding:void 0}))}}};t(u.textureColor,f!==G.OA.Opaque),t(u.textureNormal),t(u.textureOcclusion),t(u.textureEmissive),t(u.textureMetallicRoughness)}const r=u.color[0]**(1/Wt.g),p=u.color[1]**(1/Wt.g),v=u.color[2]**(1/Wt.g),g=u.emissiveFactor[0]**(1/Wt.g),_=u.emissiveFactor[1]**(1/Wt.g),x=u.emissiveFactor[2]**(1/Wt.g),T=null!=u.textureColor&&h?n.get(u.textureColor):null,E=function({normalTexture:e,metallicRoughnessTexture:t,metallicFactor:r,roughnessFactor:i,emissiveTexture:o,emissiveFactor:a,occlusionTexture:n}){return null==e&&null==t&&null==o&&(null==a||(0,c.j)(a,d.cX))&&null==n&&(null==i||1===i)&&(null==r||1===r)}({normalTexture:u.textureNormal,metallicRoughnessTexture:u.textureMetallicRoughness,metallicFactor:u.metallicFactor,roughnessFactor:u.roughnessFactor,emissiveTexture:u.textureEmissive,emissiveFactor:u.emissiveFactor,occlusionTexture:u.textureOcclusion}),S=null!=u.normalTextureTransform?.scale?u.normalTextureTransform?.scale:l.Qw;a.set(s,new wt({...i,transparent:f===G.OA.Blend,customDepthTest:G.sr.Lequal,textureAlphaMode:f,textureAlphaCutoff:u.alphaCutoff,diffuse:[r,p,v],ambient:[r,p,v],opacity:u.opacity,doubleSided:u.doubleSided,doubleSidedType:"winding-order",cullFace:u.doubleSided?G.CM.None:G.CM.Back,hasVertexColors:!!t.attributes.color,hasVertexTangents:!!t.attributes.tangent,normalType:m?Ce.g.Attribute:Ce.g.ScreenDerivative,castShadows:!0,receiveShadows:u.receiveShadows,receiveAmbientOcclusion:u.receiveAmbientOcclustion,textureId:null!=T?T.id:void 0,colorMixMode:u.colorMixMode,normalTextureId:null!=u.textureNormal&&h?n.get(u.textureNormal).id:void 0,textureAlphaPremultiplied:null!=T&&!!T.parameters.preMultiplyAlpha,occlusionTextureId:null!=u.textureOcclusion&&h?n.get(u.textureOcclusion).id:void 0,emissiveTextureId:null!=u.textureEmissive&&h?n.get(u.textureEmissive).id:void 0,metallicRoughnessTextureId:null!=u.textureMetallicRoughness&&h?n.get(u.textureMetallicRoughness).id:void 0,emissiveFactor:[g,_,x],mrrFactors:E?[...mt]:[u.metallicFactor,u.roughnessFactor,i.mrrFactors[2]],isSchematic:E,colorTextureTransformMatrix:A(u.colorTextureTransform),normalTextureTransformMatrix:A(u.normalTextureTransform),scale:[S[0],S[1]],occlusionTextureTransformMatrix:A(u.occlusionTextureTransform),emissiveTextureTransformMatrix:A(u.emissiveTextureTransform),metallicRoughnessTextureTransformMatrix:A(u.metallicRoughnessTextureTransform),...o}))}const p=a.get(s);if(r.stageResources.materials.push(p),h){const e=e=>{null!=e&&r.stageResources.textures.push(n.get(e))};e(u.textureColor),e(u.textureNormal),e(u.textureOcclusion),e(u.textureEmissive),e(u.textureMetallicRoughness)}return p}(a,e,M,t,r,s,x),{geometry:n,vertexCount:T}=function(e,t){const r=e.attributes.position.count,i=(0,E.y)(e.indices||r,e.primitiveType),a=(0,h.mc)(3*r),{typedBuffer:n,typedBufferStride:s}=e.attributes.position;(0,f.a)(a,n,e.transform,3,s);const l=[[be.K.POSITION,new U.O(a,i,3,!0)]];if(null!=e.attributes.normal){const t=(0,h.mc)(3*r),{typedBuffer:a,typedBufferStride:n}=e.attributes.normal;(0,o.yy)(qt,e.transform),(0,f.b)(t,a,qt,3,n),l.push([be.K.NORMAL,new U.O(t,i,3,!0)])}if(null!=e.attributes.tangent){const t=(0,h.mc)(4*r),{typedBuffer:a,typedBufferStride:n}=e.attributes.tangent;(0,o.yy)(qt,e.transform),(0,p.t)(t,a,qt,4,n),l.push([be.K.TANGENT,new U.O(t,i,4,!0)])}if(null!=e.attributes.texCoord0){const t=(0,h.mc)(2*r),{typedBuffer:o,typedBufferStride:a}=e.attributes.texCoord0;(0,v.n)(t,o,2,a),l.push([be.K.UV0,new U.O(t,i,2,!0)])}if(null!=e.attributes.color){const t=new Uint8Array(4*r);4===e.attributes.color.elementCount?e.attributes.color instanceof m._5?(0,p.s)(t,e.attributes.color,255):e.attributes.color instanceof m.sb?(0,_.c)(t,e.attributes.color):e.attributes.color instanceof m.Ig&&(0,p.s)(t,e.attributes.color,1/256):(t.fill(255),e.attributes.color instanceof m.Yj?(0,f.s)(t,e.attributes.color,255,4):e.attributes.color instanceof m.IN?(0,g.c)(t,e.attributes.color.typedBuffer,4,e.attributes.color.typedBufferStride):e.attributes.color instanceof m.k5&&(0,f.s)(t,e.attributes.color,1/256,4)),l.push([be.K.COLOR,new U.O(t,i,4,!0)])}return{geometry:new B.K(t,l),vertexCount:r}}(e,null!=i?i:new wt({})),C=n.boundingInfo;null!=C&&0===y&&((0,u.K8)(S,C.bbMin),(0,u.K8)(S,C.bbMax)),null!=i&&(M.stageResources.geometries.push(n),M.numberOfVertices+=T)})),w||n.push(M)})),{engineResources:n,referenceBoundingBox:S}}(a,O,V,t.skipHighLods&&null==r.specifiedLodIndex?{skipHighLods:!0}:{skipHighLods:!1,singleLodIndex:r.specifiedLodIndex});return{lods:z,referenceBoundingBox:Y,isEsriSymbolResource:a.meta.isEsriSymbolResource,isWosr:!1}}const qt=(0,a.Su)()},78720:(e,t,r)=>{var i,o;r.d(t,{KQ:()=>i}),r(53648),(o=i||(i={}))[o.Multiply=1]="Multiply",o[o.Ignore=2]="Ignore",o[o.Replace=3]="Replace",o[o.Tint=4]="Tint"},72364:(e,t,r)=>{r.d(t,{U1:()=>c,iC:()=>l});var i=r(71004),o=r(98132),a=r(90736);class n{constructor(e,t){this.layout=e,this.buffer="number"==typeof t?new ArrayBuffer(t*e.stride):t;for(const t of e.fields.keys()){const r=e.fields.get(t);this[t]=new r.constructor(this.buffer,r.offset,this.stride)}}get stride(){return this.layout.stride}get count(){return this.buffer.byteLength/this.stride}get byteLength(){return this.buffer.byteLength}getField(e,t){const r=this[e];return r&&r.elementCount===t.ElementCount&&r.elementType===t.ElementType?r:null}slice(e,t){return new n(this.layout,this.buffer.slice(e*this.stride,t*this.stride))}copyFrom(e,t=0,r=0,i=e.count){const o=this.stride;if(o%4==0){const a=new Uint32Array(e.buffer,t*o,i*o/4);new Uint32Array(this.buffer,r*o,i*o/4).set(a)}else{const a=new Uint8Array(e.buffer,t*o,i*o);new Uint8Array(this.buffer,r*o,i*o).set(a)}return this}get usedMemory(){return this.byteLength}dispose(){}}class s{constructor(e=null){this._stride=0,this._lastAligned=0,this._fields=new Map,e&&(this._stride=e.stride,e.fields.forEach((e=>this._fields.set(e[0],{...e[1],constructor:h(e[1].constructor)}))))}vec2f(e,t){return this._appendField(e,i.U7,t),this}vec2f64(e,t){return this._appendField(e,i.wT,t),this}vec3f(e,t){return this._appendField(e,i.Yj,t),this}vec3f64(e,t){return this._appendField(e,i.Ax,t),this}vec4f(e,t){return this._appendField(e,i._5,t),this}vec4f64(e,t){return this._appendField(e,i.Ip,t),this}mat3f(e,t){return this._appendField(e,i.Ko,t),this}mat3f64(e,t){return this._appendField(e,i.m_,t),this}mat4f(e,t){return this._appendField(e,i.IL,t),this}mat4f64(e,t){return this._appendField(e,i.mF,t),this}vec4u8(e,t){return this._appendField(e,i.sb,t),this}f32(e,t){return this._appendField(e,i.AZ,t),this}f64(e,t){return this._appendField(e,i.oN,t),this}u8(e,t){return this._appendField(e,i.Kg,t),this}u16(e,t){return this._appendField(e,i.E_,t),this}i8(e,t){return this._appendField(e,i.kV,t),this}vec2i8(e,t){return this._appendField(e,i.oR,t),this}vec2i16(e,t){return this._appendField(e,i.iN,t),this}vec2u8(e,t){return this._appendField(e,i.eS,t),this}vec4u16(e,t){return this._appendField(e,i.Ig,t),this}u32(e,t){return this._appendField(e,i.Ym,t),this}_appendField(e,t,r){if(this._fields.has(e))return void(0,a.Uc)(!1,`${e} already added to vertex buffer layout`);const i=t.ElementCount*(0,o.Ad)(t.ElementType),n=this._stride;this._stride+=i,this._fields.set(e,{size:i,constructor:t,offset:n,optional:r})}createBuffer(e){return new n(this,e)}createView(e){return new n(this,e)}clone(){const e=new s;return e._stride=this._stride,e._fields=new Map,this._fields.forEach(((t,r)=>e._fields.set(r,t))),e.BufferType=this.BufferType,e}get stride(){if(this._lastAligned!==this._fields.size){let e=1;this._fields.forEach((t=>e=Math.max(e,(0,o.Ad)(t.constructor.ElementType)))),this._stride=Math.floor((this._stride+e-1)/e)*e,this._lastAligned=this._fields.size}return this._stride}get fields(){return this._fields}}function l(){return new s}class c{constructor(e){this.fields=new Array,e.fields.forEach(((e,t)=>{const r={...e,constructor:u(e.constructor)};this.fields.push([t,r])})),this.stride=e.stride}}const d=[i.AZ,i.U7,i.Yj,i._5,i.Ko,i.IL,i.oN,i.wT,i.Ax,i.Ip,i.m_,i.mF,i.Kg,i.eS,i.IN,i.sb,i.E_,i.kg,i.k5,i.Ig,i.Ym,i.qZ,i.ER,i.q$,i.kV,i.oR,i.Oq,i.mq,i.Mt,i.iN,i.g7,i.OS,i.Ep,i.mI,i.Yv,i.i9];function u(e){return`${e.ElementType}_${e.ElementCount}`}function h(e){return m.get(e)}const m=new Map;d.forEach((e=>m.set(u(e),e)))},29244:(e,t,r)=>{r.d(t,{Eb:()=>Te,er:()=>Ie});var i=r(77556),o=r(68612),a=r(87104),n=r(41512),s=r(2620),l=r(98472),c=r(62484),d=r(74784),u=r(37008),h=r(22080),m=r(82448),f=r(51120),p=r(62880),v=r(38380),g=r(58632),_=r(60544),x=r(20136),T=r(21288),E=r(85256),b=r(89560);const S=1e-6,A=(0,c.Su)(),y=(0,c.Su)();function C(e,t,r,i,o,a,n,s,l,c){return function(e,t,r){let i=_e(e.maxVert[0],e.minVert[0]),o=0;for(let t=1;t<7;++t){const r=_e(e.maxVert[t],e.minVert[t]);r>i&&(i=r,o=t)}fe(t,e.minVert[o]),fe(r,e.maxVert[o])}(e,i,o),_e(i,o)<S?1:(he(n,i,o),ve(n,n),function(e,t,r,i){const{data:o,size:a}=e;let n=Number.NEGATIVE_INFINITY,s=0;for(let e=0;e<o.length;e+=a){F[0]=o[e]-t[0],F[1]=o[e+1]-t[1],F[2]=o[e+2]-t[2];const i=r[0]*F[0]+r[1]*F[1]+r[2]*F[2],a=r[0]*r[0]+r[1]*r[1]+r[2]*r[2],l=F[0]*F[0]+F[1]*F[1]+F[2]*F[2]-i*i/a;l>n&&(n=l,s=e)}return fe(i,o,s),n}(t,i,n,a)<S?2:(he(s,o,a),ve(s,s),he(l,a,i),ve(l,l),pe(r,s,n),ve(r,r),W(t,r,n,s,l,c),0))}const w=(0,c.Su)(),M=(0,c.Su)(),R=(0,c.Su)(),I=(0,c.Su)(),O=(0,c.Su)(),N=(0,c.Su)(),P=(0,c.Su)(),L=(0,c.Su)();function D(e,t,r,i,o,a,n,s,l){(function(e,t,r,i,o){!function(e,t,r,i,o){const{data:a,size:n}=e;fe(i,a),fe(o,i),r[0]=xe(j,t),r[1]=r[0];for(let e=n;e<a.length;e+=n){const n=a[e]*t[0]+a[e+1]*t[1]+a[e+2]*t[2];n<r[0]&&(r[0]=n,fe(i,a,e)),n>r[1]&&(r[1]=n,fe(o,a,e))}}(e,t,U,o,i);const a=xe(r,t);U[1]-S<=a&&(i[0]=void 0),U[0]+S>=a&&(o[0]=void 0)})(e,t,r,w,M),void 0!==w[0]&&(he(R,w,r),ve(R,R),he(I,w,i),ve(I,I),he(O,w,o),ve(O,O),pe(N,I,a),ve(N,N),pe(P,O,n),ve(P,P),pe(L,R,s),ve(L,L),W(e,N,a,I,R,l),W(e,P,n,O,I,l),W(e,L,s,R,O,l)),void 0!==M[0]&&(he(R,M,r),ve(R,R),he(I,M,i),ve(I,I),he(O,M,o),ve(O,O),pe(N,I,a),ve(N,N),pe(P,O,n),ve(P,P),pe(L,R,s),ve(L,L),W(e,N,a,I,R,l),W(e,P,n,O,I,l),W(e,L,s,R,O,l))}const F=[0,0,0],U=(0,E.Su)(),G=(0,c.Su)(),B=(0,c.Su)(),V=(0,c.Su)(),z=(0,c.Su)(),Y=(0,c.Su)(),H=(0,c.Su)();function W(e,t,r,i,o,a){if(ge(t)<S)return;pe(G,r,t),pe(B,i,t),pe(V,o,t),q(e,t,U),Y[1]=U[0],z[1]=U[1],H[1]=z[1]-Y[1];const n=[r,i,o],s=[G,B,V];for(let r=0;r<3;++r){q(e,n[r],U),Y[0]=U[0],z[0]=U[1],q(e,s[r],U),Y[2]=U[0],z[2]=U[1],H[0]=z[0]-Y[0],H[2]=z[2]-Y[2];const i=de(H);i<a.quality&&(fe(a.b0,n[r]),fe(a.b1,t),fe(a.b2,s[r]),a.quality=i)}}const j=(0,c.Su)();function q(e,t,r){const{data:i,size:o}=e;r[0]=Number.POSITIVE_INFINITY,r[1]=Number.NEGATIVE_INFINITY;for(let e=0;e<i.length;e+=o){const o=i[e]*t[0]+i[e+1]*t[1]+i[e+2]*t[2];r[0]=Math.min(r[0],o),r[1]=Math.max(r[1],o)}}function K(e,t,r){r.center=e,r.halfSize=(0,l.h)(t,t,.5),r.quaternion=s.IJ}const k=(0,c.Su)(),$=(0,c.Su)(),X=(0,c.Su)(),J=(0,c.Su)(),Z=(0,c.Su)(),Q=(0,c.Su)();function ee(e,t,r){fe(k,t),Math.abs(t[0])>Math.abs(t[1])&&Math.abs(t[0])>Math.abs(t[2])?k[0]=0:Math.abs(t[1])>Math.abs(t[2])?k[1]=0:k[2]=0,ge(k)<S&&(k[0]=k[1]=k[2]=1),pe($,t,k),ve($,$),pe(X,t,$),ve(X,X),te(e,t,$,X,J,Z),he(Q,Z,J),se(t,$,X,J,Z,Q,r)}function te(e,t,r,i,o,a){q(e,t,U),o[0]=U[0],a[0]=U[1],q(e,r,U),o[1]=U[0],a[1]=U[1],q(e,i,U),o[2]=U[0],a[2]=U[1]}const re=(0,c.Su)(),ie=(0,c.Su)(),oe=(0,c.Su)(),ae=(0,o.WK)(1,0,0,0,1,0,0,0,1),ne=(0,s.Su)();function se(e,t,r,i,o,a,n){ae[0]=e[0],ae[1]=e[1],ae[2]=e[2],ae[3]=t[0],ae[4]=t[1],ae[5]=t[2],ae[6]=r[0],ae[7]=r[1],ae[8]=r[2],n.quaternion=function(e,t){const r=t[0]+t[4]+t[8];if(r>0){let i=Math.sqrt(r+1);e[3]=.5*i,i=.5/i,e[0]=(t[5]-t[7])*i,e[1]=(t[6]-t[2])*i,e[2]=(t[1]-t[3])*i}else{let r=0;t[4]>t[0]&&(r=1),t[8]>t[3*r+r]&&(r=2);const i=(r+1)%3,o=(r+2)%3;let a=Math.sqrt(t[3*r+r]-t[3*i+i]-t[3*o+o]+1);e[r]=.5*a,a=.5/a,e[3]=(t[3*i+o]-t[3*o+i])*a,e[i]=(t[3*i+r]+t[3*r+i])*a,e[o]=(t[3*o+r]+t[3*r+o])*a}return e}(ne,ae),ue(re,i,o),me(re,re,.5),me(ie,e,re[0]),me(oe,t,re[1]),ue(ie,ie,oe),me(oe,r,re[2]),n.center=(0,l.g)(ie,ie,oe),n.halfSize=(0,l.h)(re,a,.5)}class le{constructor(e){this.minVert=new Array(7),this.maxVert=new Array(7),this.buffer=new ArrayBuffer(448);let t=0;this.minProj=new Float64Array(this.buffer,t,7),t+=56,this.maxProj=new Float64Array(this.buffer,t,7),t+=56;for(let e=0;e<7;++e)this.minVert[e]=new Float64Array(this.buffer,t,3),t+=24;for(let e=0;e<7;++e)this.maxVert[e]=new Float64Array(this.buffer,t,3),t+=24;for(let e=0;e<7;++e)this.minProj[e]=Number.POSITIVE_INFINITY,this.maxProj[e]=Number.NEGATIVE_INFINITY;const r=new Array(7),i=new Array(7),{data:o,size:a}=e;for(let e=0;e<o.length;e+=a){let t=o[e];t<this.minProj[0]&&(this.minProj[0]=t,r[0]=e),t>this.maxProj[0]&&(this.maxProj[0]=t,i[0]=e),t=o[e+1],t<this.minProj[1]&&(this.minProj[1]=t,r[1]=e),t>this.maxProj[1]&&(this.maxProj[1]=t,i[1]=e),t=o[e+2],t<this.minProj[2]&&(this.minProj[2]=t,r[2]=e),t>this.maxProj[2]&&(this.maxProj[2]=t,i[2]=e),t=o[e]+o[e+1]+o[e+2],t<this.minProj[3]&&(this.minProj[3]=t,r[3]=e),t>this.maxProj[3]&&(this.maxProj[3]=t,i[3]=e),t=o[e]+o[e+1]-o[e+2],t<this.minProj[4]&&(this.minProj[4]=t,r[4]=e),t>this.maxProj[4]&&(this.maxProj[4]=t,i[4]=e),t=o[e]-o[e+1]+o[e+2],t<this.minProj[5]&&(this.minProj[5]=t,r[5]=e),t>this.maxProj[5]&&(this.maxProj[5]=t,i[5]=e),t=o[e]-o[e+1]-o[e+2],t<this.minProj[6]&&(this.minProj[6]=t,r[6]=e),t>this.maxProj[6]&&(this.maxProj[6]=t,i[6]=e)}for(let e=0;e<7;++e){let t=r[e];fe(this.minVert[e],o,t),t=i[e],fe(this.maxVert[e],o,t)}}}class ce{constructor(){this.b0=(0,c.WK)(1,0,0),this.b1=(0,c.WK)(0,1,0),this.b2=(0,c.WK)(0,0,1),this.quality=0}}function de(e){return e[0]*e[1]+e[0]*e[2]+e[1]*e[2]}function ue(e,t,r){e[0]=t[0]+r[0],e[1]=t[1]+r[1],e[2]=t[2]+r[2]}function he(e,t,r){e[0]=t[0]-r[0],e[1]=t[1]-r[1],e[2]=t[2]-r[2]}function me(e,t,r){e[0]=t[0]*r,e[1]=t[1]*r,e[2]=t[2]*r}function fe(e,t,r=0){e[0]=t[r],e[1]=t[r+1],e[2]=t[r+2]}function pe(e,t,r){const i=t[0],o=t[1],a=t[2],n=r[0],s=r[1],l=r[2];e[0]=o*l-a*s,e[1]=a*n-i*l,e[2]=i*s-o*n}function ve(e,t){const r=t[0]*t[0]+t[1]*t[1]+t[2]*t[2];if(r>0){const i=1/Math.sqrt(r);e[0]=t[0]*i,e[1]=t[1]*i,e[2]=t[2]*i}}function ge(e){return e[0]*e[0]+e[1]*e[1]+e[2]*e[2]}function _e(e,t){const r=t[0]-e[0],i=t[1]-e[1],o=t[2]-e[2];return r*r+i*i+o*o}function xe(e,t){return e[0]*t[0]+e[1]*t[1]+e[2]*t[2]}class Te{constructor(e=c.cX,t=We,r=s.IJ){this._data=[e[0],e[1],e[2],t[0],t[1],t[2],r[0],r[1],r[2],r[3]]}clone(){const e=new Te;return e._data=this._data.slice(),e}invalidate(){this._data[3]=-1}get isValid(){return this._data[3]>=0}static fromData(e){const t=new Te;return t._data=e.slice(),t}static fromJSON(e){return new Te(e.center,e.halfSize,e.quaternion)}copy(e){this._data=e.data.slice()}get center(){return(0,l.s)(x.Wq.get(),this._data[0],this._data[1],this._data[2])}get centerX(){return this._data[0]}get centerY(){return this._data[1]}get centerZ(){return this._data[2]}getCenter(e){return e[0]=this._data[0],e[1]=this._data[1],e[2]=this._data[2],e}set center(e){this._data[0]=e[0],this._data[1]=e[1],this._data[2]=e[2]}setCenter(e,t,r){this._data[0]=e,this._data[1]=t,this._data[2]=r}get halfSize(){return(0,l.s)(x.Wq.get(),this._data[3],this._data[4],this._data[5])}get halfSizeX(){return this._data[3]}get halfSizeY(){return this._data[4]}get halfSizeZ(){return this._data[5]}getHalfSize(e){return e[0]=this._data[3],e[1]=this._data[4],e[2]=this._data[5],e}set halfSize(e){this._data[3]=e[0],this._data[4]=e[1],this._data[5]=e[2]}get quaternion(){return(0,n.WO)(x.QX.get(),this._data[6],this._data[7],this._data[8],this._data[9])}getQuaternion(e){return e[0]=this._data[6],e[1]=this._data[7],e[2]=this._data[8],e[3]=this._data[9],e}set quaternion(e){this._data[6]=e[0],this._data[7]=e[1],this._data[8]=e[2],this._data[9]=e[3]}get data(){return this._data}getCorners(e){const t=Ee,r=this._data;t[0]=r[6],t[1]=r[7],t[2]=r[8],t[3]=r[9];for(let i=0;i<8;++i){const o=e[i];o[0]=(1&i?-1:1)*r[3],o[1]=(2&i?-1:1)*r[4],o[2]=(4&i?-1:1)*r[5],(0,l.u)(o,o,t),o[0]+=r[0],o[1]+=r[1],o[2]+=r[2]}}isVisible(e){return this.intersectPlane(e[0])<=0&&this.intersectPlane(e[1])<=0&&this.intersectPlane(e[2])<=0&&this.intersectPlane(e[3])<=0&&this.intersectPlane(e[4])<=0&&this.intersectPlane(e[5])<=0}get radius(){const e=this._data[3],t=this._data[4],r=this._data[5];return Math.sqrt(e*e+t*t+r*r)}intersectSphere(e){Ae[0]=this._data[0]-e[0],Ae[1]=this._data[1]-e[1],Ae[2]=this._data[2]-e[2];const t=this.getQuaternion(be);return(0,n.fO)(Ee,t),(0,l.u)(Ae,Ae,Ee),(0,l.v)(Ae,Ae),ye[0]=Math.min(Ae[0],this._data[3]),ye[1]=Math.min(Ae[1],this._data[4]),ye[2]=Math.min(Ae[2],this._data[5]),(0,l.w)(ye,Ae)<e[3]*e[3]}intersectSphereWithMBS(e,t=this.radius){const r=this._data;Ae[0]=r[0]-e[0],Ae[1]=r[1]-e[1],Ae[2]=r[2]-e[2];const i=e[3],o=i+t;return!((0,l.x)(Ae)>o*o)&&(Ee[0]=-r[6],Ee[1]=-r[7],Ee[2]=-r[8],Ee[3]=r[9],(0,l.u)(Ae,Ae,Ee),(0,l.v)(Ae,Ae),ye[0]=Math.min(Ae[0],r[3]),ye[1]=Math.min(Ae[1],r[4]),ye[2]=Math.min(Ae[2],r[5]),(0,l.w)(ye,Ae)<i*i)}intersectPlane(e){const t=e[0]*this._data[0]+e[1]*this._data[1]+e[2]*this._data[2]+e[3],r=this.projectedRadius((0,g.Oq)(e));return t>r?1:t<-r?-1:0}intersectRay(e,t,r=0){const i=this._data,o=Ee;o[0]=-i[6],o[1]=-i[7],o[2]=-i[8],o[3]=i[9],Ae[0]=e[0]-i[0],Ae[1]=e[1]-i[1],Ae[2]=e[2]-i[2];const a=(0,l.u)(Ae,Ae,Ee),n=(0,l.u)(ye,t,Ee);let s=-1/0,c=1/0;const d=this.getHalfSize(Ue);for(let e=0;e<3;e++){const t=a[e],i=n[e],o=d[e]+r;if(Math.abs(i)>1e-6){const e=(o-t)/i,r=(-o-t)/i;s=Math.max(s,Math.min(e,r)),c=Math.min(c,Math.max(e,r))}else if(t>o||t<-o)return!1}return s<=c}projectedArea(e,t,r,o){const a=this.getQuaternion(be);(0,n.fO)(Ee,a),Ae[0]=e[0]-this._data[0],Ae[1]=e[1]-this._data[1],Ae[2]=e[2]-this._data[2],(0,l.u)(Ae,Ae,Ee);const s=this.getHalfSize(Ue),c=Ae[0]<-s[0]?-1:Ae[0]>s[0]?1:0,u=Ae[1]<-s[1]?-1:Ae[1]>s[1]?1:0,h=Ae[2]<-s[2]?-1:Ae[2]>s[2]?1:0,m=Math.abs(c)+Math.abs(u)+Math.abs(h);if(0===m)return 1/0;const f=1===m?4:6,p=6*(c+3*u+9*h+13);(0,i.KU)(Ge,a),(0,i.aU)(Ge,Ge,s);const v=this.getCenter(De);for(let e=0;e<f;e++){const r=Re[p+e];(0,l.s)(Ae,((1&r)<<1)-1,(2&r)-1,((4&r)>>1)-1),(0,l.t)(Ae,Ae,Ge),(0,l.g)(Ce,v,Ae),Ce[3]=1,(0,d.t)(Ce,Ce,t);const i=1/Math.max(1e-6,Ce[3]);Me[2*e]=Ce[0]*i,Me[2*e+1]=Ce[1]*i}const g=2*f-2;let _=Me[0]*(Me[3]-Me[g+1])+Me[g]*(Me[1]-Me[g-1]);for(let e=2;e<g;e+=2)_+=Me[e]*(Me[e+3]-Me[e-1]);return Math.abs(_)*r*o*.125}projectedRadius(e){const t=this.getQuaternion(be);return(0,n.fO)(Ee,t),(0,l.u)(Ae,e,Ee),Math.abs(Ae[0]*this._data[3])+Math.abs(Ae[1]*this._data[4])+Math.abs(Ae[2]*this._data[5])}minimumDistancePlane(e){return e[0]*this._data[0]+e[1]*this._data[1]+e[2]*this._data[2]+e[3]-this.projectedRadius((0,g.Oq)(e))}maximumDistancePlane(e){return e[0]*this._data[0]+e[1]*this._data[1]+e[2]*this._data[2]+e[3]+this.projectedRadius((0,g.Oq)(e))}toAaBoundingBox(e){const t=this.getQuaternion(be),r=(0,i.KU)(Ge,t),o=this._data[3]*Math.abs(r[0])+this._data[4]*Math.abs(r[3])+this._data[5]*Math.abs(r[6]),a=this._data[3]*Math.abs(r[1])+this._data[4]*Math.abs(r[4])+this._data[5]*Math.abs(r[7]),n=this._data[3]*Math.abs(r[2])+this._data[4]*Math.abs(r[5])+this._data[5]*Math.abs(r[8]);e[0]=this._data[0]-o,e[1]=this._data[1]-a,e[2]=this._data[2]-n,e[3]=this._data[0]+o,e[4]=this._data[1]+a,e[5]=this._data[2]+n}transform(e,t,r,i=0,o=(0,m._s)(r),a=(0,m._s)(t),s=(0,v.Mx)(t,a)){if(r===o)t.isGeographic?function(e,t,r,i,o=(0,m._s)(r)){const a=(0,h.e0)(r),s=1+Math.max(0,i)/(a.radius+e.centerZ);e.getCenter(Le),Le[2]+=i,(0,p.projectBuffer)(Le,r,0,Le,o,0,1),t.center=Le;const c=e.getQuaternion(be);t.quaternion=c,(0,n.fO)(Ee,c),(0,l.s)(ze,0,0,1),(0,l.u)(ze,ze,Ee);const d=e.getHalfSize(Ue);(0,l.s)(ze,d[0]*Math.abs(ze[0]),d[1]*Math.abs(ze[1]),d[2]*Math.abs(ze[2])),(0,l.h)(ze,ze,a.inverseFlattening),(0,l.g)(ze,d,ze),t.halfSize=(0,l.h)(ze,ze,s)}(this,e,t,i,a):function(e,t,r,i,o=(0,m._s)(r),a=(0,v.Mx)(r,o)){e.getCorners(Ve),e.getCenter(Le),Le[2]+=i,(0,f.y)(r,Le,Be,o),t.setCenter(Be[12],Be[13],Be[14]);const s=2*Math.sqrt(1+Be[0]+Be[5]+Be[10]);Ee[0]=(Be[6]-Be[9])/s,Ee[1]=(Be[8]-Be[2])/s,Ee[2]=(Be[1]-Be[4])/s,Ee[3]=.25*s;const c=e.getQuaternion(be);t.quaternion=(0,n.iW)(Ee,Ee,c),(0,n.fO)(Ee,Ee),(0,l.s)(Ye,0,0,0);const d=t.getCenter(Fe);for(const e of Ve)e[2]+=i,a(e,0,e,0),(0,l.z)(ze,e,d),(0,l.u)(ze,ze,Ee),(0,l.v)(ze,ze),(0,l.C)(Ye,Ye,ze);t.halfSize=Ye}(this,e,t,i,a,s);else if(t.isWGS84&&(r.isWebMercator||(0,_.GK)(r)))!function(e,t,r,i,o){t.getCenter(De),De[2]+=o;const a=(0,m._s)(r);(0,p.projectBuffer)(De,e,0,De,a,0,1),Oe(a,t,De,r,i)}(t,this,r,e,i);else if(t.isWebMercator&&(0,_.GK)(r))!function(e,t,r,i,o){t.getCenter(De),De[2]+=o,Oe(e,t,De,r,i)}(t,this,r,e,i);else{const o=this.getCenter(De);o[2]+=i,(0,p.projectBuffer)(o,t,0,o,r,0,1),e.center=o,this!==e&&(e.quaternion=this.getQuaternion(be),e.halfSize=this.getHalfSize(Ue))}}}const Ee=(0,s.Su)(),be=(0,s.Su)(),Se=(0,s.Su)(),Ae=(0,c.Su)(),ye=(0,c.Su)(),Ce=(0,u.Su)();function we(e,t=new Te){return function(e,t){const{data:r,size:i}=e,o=r.length/i;if(o<=0)return;const a=new le(e);ue(A,a.minProj,a.maxProj),me(A,A,.5),he(y,a.maxProj,a.minProj);const n=de(y),s=new ce;s.quality=n,o<14&&(e=new b.Y(new Float64Array(a.buffer,112,42),3));const l=(0,c.Su)(),d=(0,c.Su)(),u=(0,c.Su)(),h=(0,c.Su)(),m=(0,c.Su)(),f=(0,c.Su)(),p=(0,c.Su)();switch(C(a,e,p,l,d,u,h,m,f,s)){case 1:return void K(A,y,t);case 2:return void ee(e,h,t)}D(e,p,l,d,u,h,m,f,s),te(e,s.b0,s.b1,s.b2,J,Z);const v=(0,c.Su)();he(v,Z,J),s.quality=de(v),s.quality<n?se(s.b0,s.b1,s.b2,J,Z,v,t):K(A,y,t)}(e,t),t}const Me=[.1,.2,.3,.4,.5,.6,.7,.8,.9,1,1.1,1.2],Re=(()=>{const e=new Int8Array(162);let t=0;const r=r=>{for(let i=0;i<r.length;i++)e[t+i]=r[i];t+=6};return r([6,2,3,1,5,4]),r([0,2,3,1,5,4]),r([0,2,3,7,5,4]),r([0,1,3,2,6,4]),r([0,1,3,2,0,0]),r([0,1,5,7,3,2]),r([0,1,3,7,6,4]),r([0,1,3,7,6,2]),r([0,1,5,7,6,2]),r([0,1,5,4,6,2]),r([0,1,5,4,0,0]),r([0,1,3,7,5,4]),r([0,2,6,4,0,0]),r([0,0,0,0,0,0]),r([1,3,7,5,0,0]),r([2,3,7,6,4,0]),r([2,3,7,6,0,0]),r([2,3,1,5,7,6]),r([0,1,5,7,6,2]),r([0,1,5,7,6,4]),r([0,1,3,7,6,4]),r([4,5,7,6,2,0]),r([4,5,7,6,0,0]),r([4,5,1,3,7,6]),r([0,2,3,7,5,4]),r([6,2,3,7,5,4]),r([6,2,3,1,5,4]),e})();function Ie(e,t,r,i,o){const a=e.getQuaternion(be);o.quaternion=a,(0,n.fO)(Ee,a);const s=e.getCenter(De),d=e.getHalfSize(Ue);if(i===T.UZ.Global){(0,l.u)(ze,s,Ee),(0,l.v)(Ye,ze),(0,l.y)(He,Ye,d),(0,l.z)(He,Ye,He);const i=(0,l.l)(He);(0,l.g)(He,Ye,d);const a=(0,l.l)(He);if(i<r)o.center=s,(0,l.s)(ze,r,r,r),o.halfSize=(0,l.g)(ze,d,ze);else{const n=a>0?1+t/a:1,s=i>0?1+r/i:1,c=(s+n)/2,u=(s-n)/2;(0,l.h)(He,Ye,u),o.halfSize=(0,l.r)(He,He,d,c),(0,l.h)(He,Ye,c),(0,l.r)(He,He,d,u),(0,l.A)(ze,ze),(0,l.B)(ze,He,ze);const h=e.getQuaternion(Se);o.center=(0,l.u)(ze,ze,h)}}else{o.center=(0,l.r)(ze,s,c.wx,(r+t)/2);const e=(0,l.u)(ze,c.wx,Ee);(0,l.v)(e,e),o.halfSize=(0,l.r)(Ye,d,e,(r-t)/2)}return o}function Oe(e,t,r,o,a){const n=t.getQuaternion(be),s=(0,i.KU)(Ge,n),l=t.getHalfSize(Ue);for(let e=0;e<8;++e){for(let t=0;t<3;++t)Le[t]=l[t]*(0!=(e&1<<t)?-1:1);for(let t=0;t<3;++t){let i=r[t];for(let e=0;e<3;++e)i+=Le[e]*s[3*e+t];Ne[3*e+t]=i}}(0,p.projectBuffer)(Ne,e,0,Ne,o,0,8),we(Pe,a)}const Ne=new Array(24),Pe=new b.Y(Ne,3),Le=(0,c.Su)(),De=(0,c.Su)(),Fe=(0,c.Su)(),Ue=(0,c.Su)(),Ge=(0,o.Su)(),Be=(0,a.Su)(),Ve=[[0,0,0],[0,0,0],[0,0,0],[0,0,0],[0,0,0],[0,0,0],[0,0,0],[0,0,0]],ze=(0,c.Su)(),Ye=(0,c.Su)(),He=(0,c.Su)(),We=(0,c.WK)(-1,-1,-1)},90124:(e,t,r)=>{r.d(t,{Er:()=>d,G8:()=>c,_J:()=>l});var i=r(80324),o=r(44840),a=r(48688),n=r(60288);function s(e){e.varyings.add("linearDepth","float")}function l(e){e.vertex.uniforms.add(new a.$("nearFar",((e,t)=>t.camera.nearFar)))}function c(e){e.vertex.code.add(n.Y`float calculateLinearDepth(vec2 nearFar,float z) {
return (-z - nearFar[0]) / (nearFar[1] - nearFar[0]);
}`)}function d(e,t){const{vertex:r}=e;switch(t.output){case i.mC.Color:if(t.receiveShadows)return s(e),void r.code.add(n.Y`void forwardLinearDepth() { linearDepth = gl_Position.w; }`);break;case i.mC.LinearDepth:case i.mC.Shadow:case i.mC.ShadowHighlight:case i.mC.ShadowExcludeHighlight:return e.include(o.AX,t),s(e),l(e),c(e),void r.code.add(n.Y`void forwardLinearDepth() {
linearDepth = calculateLinearDepth(nearFar, vPosition_view.z);
}`)}r.code.add(n.Y`void forwardLinearDepth() {}`)}},97760:(e,t,r)=>{r.d(t,{m:()=>o});var i=r(60288);function o(e){e.vertex.code.add(i.Y`vec4 offsetBackfacingClipPosition(vec4 posClip, vec3 posWorld, vec3 normalWorld, vec3 camPosWorld) {
vec3 camToVert = posWorld - camPosWorld;
bool isBackface = dot(camToVert, normalWorld) > 0.0;
if (isBackface) {
posClip.z += 0.0000003 * posClip.w;
}
return posClip;
}`)}},99556:(e,t,r)=>{r.d(t,{Y:()=>a});var i=r(60288),o=r(4636);function a(e,t=!0){e.attributes.add(o.K.POSITION,"vec2"),t&&e.varyings.add("uv","vec2"),e.vertex.code.add(i.Y`
    void main(void) {
      gl_Position = vec4(position, 0.0, 1.0);
      ${t?i.Y`uv = position * 0.5 + vec2(0.5);`:""}
    }
  `)}},80324:(e,t,r)=>{var i;function o(e){return e===i.Shadow||e===i.ShadowHighlight||e===i.ShadowExcludeHighlight}function a(e){return function(e){return function(e){return s(e)||n(e)}(e)||e===i.LinearDepth}(e)||e===i.Normal}function n(e){return e===i.Highlight||e===i.ObjectAndLayerIdColor}function s(e){return e===i.Color||e===i.Alpha}r.d(t,{KQ:()=>o,mC:()=>i,ue:()=>a}),function(e){e[e.Color=0]="Color",e[e.LinearDepth=1]="LinearDepth",e[e.Depth=2]="Depth",e[e.Normal=3]="Normal",e[e.Shadow=4]="Shadow",e[e.ShadowHighlight=5]="ShadowHighlight",e[e.ShadowExcludeHighlight=6]="ShadowExcludeHighlight",e[e.Highlight=7]="Highlight",e[e.Alpha=8]="Alpha",e[e.ObjectAndLayerIdColor=9]="ObjectAndLayerIdColor",e[e.CompositeColor=10]="CompositeColor",e[e.COUNT=11]="COUNT"}(i||(i={}))},68828:(e,t,r)=>{r.d(t,{A3:()=>c});var i=r(15008),o=r(87104),a=r(98472),n=r(62484),s=r(80645),l=(r(56124),r(60288));function c(e,t){!function(e,t,...r){if(!t.hasSlicePlane){const r=l.Y`#define rejectBySlice(_pos_) false
#define discardBySlice(_pos_) {}
#define highlightSlice(_color_, _pos_) (_color_)`;return t.hasSliceInVertexProgram&&e.vertex.code.add(r),void e.fragment.code.add(r)}t.hasSliceInVertexProgram&&e.vertex.uniforms.add(...r),e.fragment.uniforms.add(...r);const i=l.Y`struct SliceFactors {
float front;
float side0;
float side1;
float side2;
float side3;
};
SliceFactors calculateSliceFactors(vec3 pos) {
vec3 rel = pos - slicePlaneOrigin;
vec3 slicePlaneNormal = -cross(slicePlaneBasis1, slicePlaneBasis2);
float slicePlaneW = -dot(slicePlaneNormal, slicePlaneOrigin);
float basis1Len2 = dot(slicePlaneBasis1, slicePlaneBasis1);
float basis2Len2 = dot(slicePlaneBasis2, slicePlaneBasis2);
float basis1Dot = dot(slicePlaneBasis1, rel);
float basis2Dot = dot(slicePlaneBasis2, rel);
return SliceFactors(
dot(slicePlaneNormal, pos) + slicePlaneW,
-basis1Dot - basis1Len2,
basis1Dot - basis1Len2,
-basis2Dot - basis2Len2,
basis2Dot - basis2Len2
);
}
bool sliceByFactors(SliceFactors factors) {
return factors.front < 0.0
&& factors.side0 < 0.0
&& factors.side1 < 0.0
&& factors.side2 < 0.0
&& factors.side3 < 0.0;
}
bool sliceEnabled() {
return dot(slicePlaneBasis1, slicePlaneBasis1) != 0.0;
}
bool sliceByPlane(vec3 pos) {
return sliceEnabled() && sliceByFactors(calculateSliceFactors(pos));
}
#define rejectBySlice(_pos_) sliceByPlane(_pos_)
#define discardBySlice(_pos_) { if (sliceByPlane(_pos_)) discard; }`,o=l.Y`vec4 applySliceHighlight(vec4 color, vec3 pos) {
SliceFactors factors = calculateSliceFactors(pos);
const float HIGHLIGHT_WIDTH = 1.0;
const vec4 HIGHLIGHT_COLOR = vec4(0.0, 0.0, 0.0, 0.3);
factors.front /= (2.0 * HIGHLIGHT_WIDTH) * fwidth(factors.front);
factors.side0 /= (2.0 * HIGHLIGHT_WIDTH) * fwidth(factors.side0);
factors.side1 /= (2.0 * HIGHLIGHT_WIDTH) * fwidth(factors.side1);
factors.side2 /= (2.0 * HIGHLIGHT_WIDTH) * fwidth(factors.side2);
factors.side3 /= (2.0 * HIGHLIGHT_WIDTH) * fwidth(factors.side3);
if (sliceByFactors(factors)) {
return color;
}
float highlightFactor = (1.0 - step(0.5, factors.front))
* (1.0 - step(0.5, factors.side0))
* (1.0 - step(0.5, factors.side1))
* (1.0 - step(0.5, factors.side2))
* (1.0 - step(0.5, factors.side3));
return mix(color, vec4(HIGHLIGHT_COLOR.rgb, color.a), highlightFactor * HIGHLIGHT_COLOR.a);
}`,a=t.hasSliceHighlight?l.Y`
        ${o}
        #define highlightSlice(_color_, _pos_) (sliceEnabled() ? applySliceHighlight(_color_, _pos_) : (_color_))
      `:l.Y`#define highlightSlice(_color_, _pos_) (_color_)`;t.hasSliceInVertexProgram&&e.vertex.code.add(i),e.fragment.code.add(i),e.fragment.code.add(a)}(e,t,new s.m("slicePlaneOrigin",((e,r)=>function(e,t,r){if(null==r.slicePlane)return n.cX;const i=d(e,t,r),o=u(i,r.slicePlane),s=h(e,i,r);return null!=s?(0,a.e)(p,o,s):o}(t,e,r))),new s.m("slicePlaneBasis1",((e,r)=>m(t,e,r,r.slicePlane?.basis1))),new s.m("slicePlaneBasis2",((e,r)=>m(t,e,r,r.slicePlane?.basis2))))}function d(e,t,r){return e.instancedDoublePrecision?(0,a.s)(f,r.camera.viewInverseTransposeMatrix[3],r.camera.viewInverseTransposeMatrix[7],r.camera.viewInverseTransposeMatrix[11]):t.slicePlaneLocalOrigin}function u(e,t){return null!=e?(0,a.f)(p,t.origin,e):t.origin}function h(e,t,r){return e.hasSliceTranslatedView?null!=t?(0,i.GS)(g,r.camera.viewMatrix,t):r.camera.viewMatrix:null}function m(e,t,r,i){if(null==i||null==r.slicePlane)return n.cX;const o=d(e,t,r),s=u(o,r.slicePlane),l=h(e,o,r);return null!=l?((0,a.g)(v,i,s),(0,a.e)(p,s,l),(0,a.e)(v,v,l),(0,a.f)(v,v,p)):i}l.a;const f=(0,n.Su)(),p=(0,n.Su)(),v=(0,n.Su)(),g=(0,o.Su)()},70984:(e,t,r)=>{r.d(t,{w:()=>a});var i=r(90124),o=r(60288);function a(e){(0,i.G8)(e),e.vertex.code.add(o.Y`vec4 transformPositionWithDepth(mat4 proj, mat4 view, vec3 pos, vec2 nearFar, out float depth) {
vec4 eye = view * vec4(pos, 1.0);
depth = calculateLinearDepth(nearFar,eye.z);
return proj * eye;
}`),e.vertex.code.add(o.Y`vec4 transformPosition(mat4 proj, mat4 view, vec3 pos) {
return proj * (view * vec4(pos, 1.0));
}`)}},11925:(e,t,r)=>{r.d(t,{As:()=>E,i0:()=>x});var i=r(41948),o=r(77556),a=r(68612),n=r(87104),s=r(98472),l=r(62484),c=r(80324),d=r(36752),u=r(95224),h=r(80645),m=r(60288),f=r(11388),p=r(47572),v=r(74912),g=r(4636),_=r(5480);class x extends v.K{constructor(){super(...arguments),this.instancedDoublePrecision=!1,this.hasModelTransformation=!1}}(0,i._)([(0,v.y)()],x.prototype,"instancedDoublePrecision",void 0),(0,i._)([(0,v.y)()],x.prototype,"hasModelTransformation",void 0),m.a;const T=(0,a.Su)();function E(e,t){const r=t.hasModelTransformation,i=t.instancedDoublePrecision;r&&(e.vertex.uniforms.add(new p.O("model",(e=>e.modelTransformation??n.IJ))),e.vertex.uniforms.add(new f.q("normalLocalOriginFromModel",(e=>((0,o.yy)(T,e.modelTransformation??n.IJ),T))))),t.instanced&&i&&(e.attributes.add(g.K.INSTANCEMODELORIGINHI,"vec3"),e.attributes.add(g.K.INSTANCEMODELORIGINLO,"vec3"),e.attributes.add(g.K.INSTANCEMODEL,"mat3"),e.attributes.add(g.K.INSTANCEMODELNORMAL,"mat3"));const a=e.vertex;i&&(a.include(d.O,t),a.uniforms.add(new h.m("viewOriginHi",((e,t)=>(0,_.KM)((0,s.s)(b,t.camera.viewInverseTransposeMatrix[3],t.camera.viewInverseTransposeMatrix[7],t.camera.viewInverseTransposeMatrix[11]),b))),new h.m("viewOriginLo",((e,t)=>(0,_.Ky)((0,s.s)(b,t.camera.viewInverseTransposeMatrix[3],t.camera.viewInverseTransposeMatrix[7],t.camera.viewInverseTransposeMatrix[11]),b))))),a.code.add(m.Y`
    vec3 getVertexInLocalOriginSpace() {
      return ${r?i?"(model * vec4(instanceModel * localPosition().xyz, 1.0)).xyz":"(model * localPosition()).xyz":i?"instanceModel * localPosition().xyz":"localPosition().xyz"};
    }

    vec3 subtractOrigin(vec3 _pos) {
      ${i?m.Y`
          // Negated inputs are intentionally the first two arguments. The other way around the obfuscation in dpAdd() stopped
          // working for macOS 14+ and iOS 17+.
          
          vec3 originDelta = dpAdd(-instanceModelOriginHi, -instanceModelOriginLo, viewOriginHi, viewOriginLo);
          return _pos - originDelta;`:"return vpos;"}
    }
    `),a.code.add(m.Y`
    vec3 dpNormal(vec4 _normal) {
      return normalize(${r?i?"normalLocalOriginFromModel * (instanceModelNormal * _normal.xyz)":"normalLocalOriginFromModel * _normal.xyz":i?"instanceModelNormal * _normal.xyz":"_normal.xyz"});
    }
    `),t.output===c.mC.Normal&&((0,u.CI)(a),a.code.add(m.Y`
    vec3 dpNormalView(vec4 _normal) {
      return normalize((viewNormal * ${r?i?"vec4(normalLocalOriginFromModel * (instanceModelNormal * _normal.xyz), 1.0)":"vec4(normalLocalOriginFromModel * _normal.xyz, 1.0)":i?"vec4(instanceModelNormal * _normal.xyz, 1.0)":"_normal"}).xyz);
    }
    `)),t.hasVertexTangents&&a.code.add(m.Y`
    vec4 dpTransformVertexTangent(vec4 _tangent) {
      ${r?i?"return vec4(normalLocalOriginFromModel * (instanceModelNormal * _tangent.xyz), _tangent.w);":"return vec4(normalLocalOriginFromModel * _tangent.xyz, _tangent.w);":i?"return vec4(instanceModelNormal * _tangent.xyz, _tangent.w);":"return _tangent;"}
    }`)}const b=(0,l.Su)()},88196:(e,t,r)=>{r.d(t,{g:()=>i,k:()=>l});var i,o,a=r(44584),n=r(60288),s=r(4636);function l(e,t){switch(t.normalType){case i.Compressed:e.attributes.add(s.K.NORMALCOMPRESSED,"vec2"),e.vertex.code.add(n.Y`vec3 decompressNormal(vec2 normal) {
float z = 1.0 - abs(normal.x) - abs(normal.y);
return vec3(normal + sign(normal) * min(z, 0.0), z);
}
vec3 normalModel() {
return decompressNormal(normalCompressed);
}`);break;case i.Attribute:e.attributes.add(s.K.NORMAL,"vec3"),e.vertex.code.add(n.Y`vec3 normalModel() {
return normal;
}`);break;case i.ScreenDerivative:e.fragment.code.add(n.Y`vec3 screenDerivativeNormal(vec3 positionView) {
return normalize(cross(dFdx(positionView), dFdy(positionView)));
}`);break;default:(0,a.aM)(t.normalType);case i.COUNT:case i.Ground:}}(o=i||(i={}))[o.Attribute=0]="Attribute",o[o.Compressed=1]="Compressed",o[o.Ground=2]="Ground",o[o.ScreenDerivative=3]="ScreenDerivative",o[o.COUNT=4]="COUNT"},32704:(e,t,r)=>{r.d(t,{s:()=>a});var i=r(60288),o=r(4636);function a(e){e.attributes.add(o.K.POSITION,"vec3"),e.vertex.code.add(i.Y`vec3 positionModel() { return position; }`)}},49168:(e,t,r)=>{r.d(t,{i:()=>c});var i=r(78720),o=r(60288);function a(e){e.vertex.code.add(o.Y`
    vec4 decodeSymbolColor(vec4 symbolColor, out int colorMixMode) {
      float symbolAlpha = 0.0;

      const float maxTint = 85.0;
      const float maxReplace = 170.0;
      const float scaleAlpha = 3.0;

      if (symbolColor.a > maxReplace) {
        colorMixMode = ${o.Y.int(i.KQ.Multiply)};
        symbolAlpha = scaleAlpha * (symbolColor.a - maxReplace);
      } else if (symbolColor.a > maxTint) {
        colorMixMode = ${o.Y.int(i.KQ.Replace)};
        symbolAlpha = scaleAlpha * (symbolColor.a - maxTint);
      } else if (symbolColor.a > 0.0) {
        colorMixMode = ${o.Y.int(i.KQ.Tint)};
        symbolAlpha = scaleAlpha * symbolColor.a;
      } else {
        colorMixMode = ${o.Y.int(i.KQ.Multiply)};
        symbolAlpha = 0.0;
      }

      return vec4(symbolColor.r, symbolColor.g, symbolColor.b, symbolAlpha);
    }
  `)}var n=r(5512),s=r(4636),l=r(22592);function c(e,t){t.hasSymbolColors?(e.include(a),e.attributes.add(s.K.SYMBOLCOLOR,"vec4"),e.varyings.add("colorMixMode","mediump float"),e.vertex.code.add(o.Y`int symbolColorMixMode;
vec4 getSymbolColor() {
return decodeSymbolColor(symbolColor, symbolColorMixMode) * 0.003921568627451;
}
void forwardColorMixMode() {
colorMixMode = float(symbolColorMixMode) + 0.5;
}`)):(e.fragment.uniforms.add(new n.i("colorMixMode",(e=>l.An[e.colorMixMode]))),e.vertex.code.add(o.Y`vec4 getSymbolColor() { return vec4(1.0); }
void forwardColorMixMode() {}`))}},55164:(e,t,r)=>{r.d(t,{I:()=>l,i:()=>i});var i,o,a=r(44584),n=r(60288),s=r(4636);function l(e,t){switch(t.textureCoordinateType){case i.Default:return e.attributes.add(s.K.UV0,"vec2"),e.varyings.add("vuv0","vec2"),void e.vertex.code.add(n.Y`void forwardTextureCoordinates() {
vuv0 = uv0;
}`);case i.Compressed:return e.attributes.add(s.K.UV0,"vec2"),e.varyings.add("vuv0","vec2"),void e.vertex.code.add(n.Y`vec2 getUV0() {
return uv0 / 16384.0;
}
void forwardTextureCoordinates() {
vuv0 = getUV0();
}`);case i.Atlas:return e.attributes.add(s.K.UV0,"vec2"),e.varyings.add("vuv0","vec2"),e.attributes.add(s.K.UVREGION,"vec4"),e.varyings.add("vuvRegion","vec4"),void e.vertex.code.add(n.Y`void forwardTextureCoordinates() {
vuv0 = uv0;
vuvRegion = uvRegion;
}`);default:(0,a.aM)(t.textureCoordinateType);case i.None:return void e.vertex.code.add(n.Y`void forwardTextureCoordinates() {}`);case i.COUNT:return}}(o=i||(i={}))[o.None=0]="None",o[o.Default=1]="Default",o[o.Atlas=2]="Atlas",o[o.Compressed=3]="Compressed",o[o.COUNT=4]="COUNT"},41804:(e,t,r)=>{r.d(t,{O:()=>a});var i=r(60288),o=r(4636);function a(e,t){t.hasVertexColors?(e.attributes.add(o.K.COLOR,"vec4"),e.varyings.add("vColor","vec4"),e.vertex.code.add(i.Y`void forwardVertexColor() { vColor = color; }`),e.vertex.code.add(i.Y`void forwardNormalizedVertexColor() { vColor = color * 0.003921568627451; }`)):e.vertex.code.add(i.Y`void forwardVertexColor() {}
void forwardNormalizedVertexColor() {}`)}},60072:(e,t,r)=>{r.d(t,{Yj:()=>h,u8:()=>u,yO:()=>m});var i=r(44584),o=r(68612),a=r(37008),n=r(88196),s=r(44840),l=r(60288),c=r(44228),d=r(11388);function u(e,t){switch(t.normalType){case n.g.Attribute:case n.g.Compressed:e.include(n.k,t),e.varyings.add("vNormalWorld","vec3"),e.varyings.add("vNormalView","vec3"),e.vertex.uniforms.add(new c.o("transformNormalGlobalFromModel",(e=>e.transformNormalGlobalFromModel)),new d.q("transformNormalViewFromGlobal",(e=>e.transformNormalViewFromGlobal))),e.vertex.code.add(l.Y`void forwardNormal() {
vNormalWorld = transformNormalGlobalFromModel * normalModel();
vNormalView = transformNormalViewFromGlobal * vNormalWorld;
}`);break;case n.g.Ground:e.include(s.AX,t),e.varyings.add("vNormalWorld","vec3"),e.vertex.code.add(l.Y`
        void forwardNormal() {
          vNormalWorld = ${t.spherical?l.Y`normalize(vPositionWorldCameraRelative);`:l.Y`vec3(0.0, 0.0, 1.0);`}
        }
        `);break;case n.g.ScreenDerivative:e.vertex.code.add(l.Y`void forwardNormal() {}`);break;default:(0,i.aM)(t.normalType);case n.g.COUNT:}}class h extends s.Y5{constructor(){super(...arguments),this.transformNormalViewFromGlobal=(0,o.Su)()}}class m extends s.iS{constructor(){super(...arguments),this.transformNormalGlobalFromModel=(0,o.Su)(),this.toMapSpace=(0,a.Su)()}}},44840:(e,t,r)=>{r.d(t,{AX:()=>f,Y5:()=>p,iS:()=>v});var i=r(68612),o=r(87104),a=r(62484),n=r(32704),s=r(36752),l=r(80645),c=r(56124),d=r(60288),u=r(44228),h=r(11388),m=r(47572);function f(e,t){e.include(n.s);const r=e.vertex;r.include(s.O,t),e.varyings.add("vPositionWorldCameraRelative","vec3"),e.varyings.add("vPosition_view","vec3"),r.uniforms.add(new c.C("transformWorldFromViewTH",(e=>e.transformWorldFromViewTH)),new c.C("transformWorldFromViewTL",(e=>e.transformWorldFromViewTL)),new h.q("transformViewFromCameraRelativeRS",(e=>e.transformViewFromCameraRelativeRS)),new m.O("transformProjFromView",(e=>e.transformProjFromView)),new u.o("transformWorldFromModelRS",(e=>e.transformWorldFromModelRS)),new l.m("transformWorldFromModelTH",(e=>e.transformWorldFromModelTH)),new l.m("transformWorldFromModelTL",(e=>e.transformWorldFromModelTL))),r.code.add(d.Y`vec3 positionWorldCameraRelative() {
vec3 rotatedModelPosition = transformWorldFromModelRS * positionModel();
vec3 transform_CameraRelativeFromModel = dpAdd(
transformWorldFromModelTL,
transformWorldFromModelTH,
-transformWorldFromViewTL,
-transformWorldFromViewTH
);
return transform_CameraRelativeFromModel + rotatedModelPosition;
}`),r.code.add(d.Y`
    void forwardPosition(float fOffset) {
      vPositionWorldCameraRelative = positionWorldCameraRelative();
      if (fOffset != 0.0) {
        vPositionWorldCameraRelative += fOffset * ${t.spherical?d.Y`normalize(transformWorldFromViewTL + vPositionWorldCameraRelative)`:d.Y`vec3(0.0, 0.0, 1.0)`};
      }

      vPosition_view = transformViewFromCameraRelativeRS * vPositionWorldCameraRelative;
      gl_Position = transformProjFromView * vec4(vPosition_view, 1.0);
    }
  `),e.fragment.uniforms.add(new c.C("transformWorldFromViewTL",(e=>e.transformWorldFromViewTL))),r.code.add(d.Y`vec3 positionWorld() {
return transformWorldFromViewTL + vPositionWorldCameraRelative;
}`),e.fragment.code.add(d.Y`vec3 positionWorld() {
return transformWorldFromViewTL + vPositionWorldCameraRelative;
}`)}class p extends d.a{constructor(){super(...arguments),this.transformWorldFromViewTH=(0,a.Su)(),this.transformWorldFromViewTL=(0,a.Su)(),this.transformViewFromCameraRelativeRS=(0,i.Su)(),this.transformProjFromView=(0,o.Su)()}}class v extends d.a{constructor(){super(...arguments),this.transformWorldFromModelRS=(0,i.Su)(),this.transformWorldFromModelTH=(0,a.Su)(),this.transformWorldFromModelTL=(0,a.Su)()}}},33610:(e,t,r)=>{r.d(t,{C:()=>s});var i=r(44584),o=r(55164),a=r(60288);function n(e){e.fragment.code.add(a.Y`vec4 textureAtlasLookup(sampler2D tex, vec2 textureCoordinates, vec4 atlasRegion) {
vec2 atlasScale = atlasRegion.zw - atlasRegion.xy;
vec2 uvAtlas = fract(textureCoordinates) * atlasScale + atlasRegion.xy;
float maxdUV = 0.125;
vec2 dUVdx = clamp(dFdx(textureCoordinates), -maxdUV, maxdUV) * atlasScale;
vec2 dUVdy = clamp(dFdy(textureCoordinates), -maxdUV, maxdUV) * atlasScale;
return textureGrad(tex, uvAtlas, dUVdx, dUVdy);
}`)}function s(e,t){switch(e.include(o.I,t),t.textureCoordinateType){case o.i.Default:case o.i.Compressed:return void e.fragment.code.add(a.Y`vec4 textureLookup(sampler2D tex, vec2 uv) {
return texture(tex, uv);
}`);case o.i.Atlas:return e.include(n),void e.fragment.code.add(a.Y`vec4 textureLookup(sampler2D tex, vec2 uv) {
return textureAtlasLookup(tex, uv, vuvRegion);
}`);default:(0,i.aM)(t.textureCoordinateType);case o.i.None:case o.i.COUNT:return}}},11544:(e,t,r)=>{r.d(t,{k:()=>m});var i=r(74784),o=r(37008),a=r(98472),n=r(62484),s=r(56124),l=r(60288);function c(e){e.vertex.code.add(l.Y`float screenSizePerspectiveViewAngleDependentFactor(float absCosAngle) {
return absCosAngle * absCosAngle * absCosAngle;
}`),e.vertex.code.add(l.Y`vec3 screenSizePerspectiveScaleFactor(float absCosAngle, float distanceToCamera, vec3 params) {
return vec3(
min(params.x / (distanceToCamera - params.y), 1.0),
screenSizePerspectiveViewAngleDependentFactor(absCosAngle),
params.z
);
}`),e.vertex.code.add(l.Y`float applyScreenSizePerspectiveScaleFactorFloat(float size, vec3 factor) {
return mix(size * clamp(factor.x, factor.z, 1.0), size, factor.y);
}`),e.vertex.code.add(l.Y`float screenSizePerspectiveScaleFloat(float size, float absCosAngle, float distanceToCamera, vec3 params) {
return applyScreenSizePerspectiveScaleFactorFloat(
size,
screenSizePerspectiveScaleFactor(absCosAngle, distanceToCamera, params)
);
}`),e.vertex.code.add(l.Y`vec2 applyScreenSizePerspectiveScaleFactorVec2(vec2 size, vec3 factor) {
return mix(size * clamp(factor.x, factor.z, 1.0), size, factor.y);
}`),e.vertex.code.add(l.Y`vec2 screenSizePerspectiveScaleVec2(vec2 size, float absCosAngle, float distanceToCamera, vec3 params) {
return applyScreenSizePerspectiveScaleFactorVec2(size, screenSizePerspectiveScaleFactor(absCosAngle, distanceToCamera, params));
}`)}const d=(0,n.Su)();var u=r(95224),h=r(56192);function m(e,t){const r=e.vertex;t.hasVerticalOffset?(function(e){e.uniforms.add(new h.U("verticalOffset",((e,t)=>{const{minWorldLength:r,maxWorldLength:o,screenLength:a}=e.verticalOffset,n=Math.tan(.5*t.camera.fovY)/(.5*t.camera.fullViewport[3]),s=t.camera.pixelRatio||1;return(0,i.s)(f,a*s,n,r,o)})))}(r),t.hasScreenSizePerspective&&(e.include(c),function(e){e.uniforms.add(new s.C("screenSizePerspectiveAlignment",(e=>function(e){return(0,a.s)(d,e.parameters.divisor,e.parameters.offset,e.minScaleFactor)}(e.screenSizePerspectiveAlignment||e.screenSizePerspective))))}(r),(0,u.E7)(e.vertex,t)),r.code.add(l.Y`
      vec3 calculateVerticalOffset(vec3 worldPos, vec3 localOrigin) {
        float viewDistance = length((view * vec4(worldPos, 1.0)).xyz);
        ${t.spherical?l.Y`vec3 worldNormal = normalize(worldPos + localOrigin);`:l.Y`vec3 worldNormal = vec3(0.0, 0.0, 1.0);`}
        ${t.hasScreenSizePerspective?l.Y`
            float cosAngle = dot(worldNormal, normalize(worldPos - cameraPosition));
            float verticalOffsetScreenHeight = screenSizePerspectiveScaleFloat(verticalOffset.x, abs(cosAngle), viewDistance, screenSizePerspectiveAlignment);`:l.Y`
            float verticalOffsetScreenHeight = verticalOffset.x;`}
        // Screen sized offset in world space, used for example for line callouts
        float worldOffset = clamp(verticalOffsetScreenHeight * verticalOffset.y * viewDistance, verticalOffset.z, verticalOffset.w);
        return worldNormal * worldOffset;
      }

      vec3 addVerticalOffset(vec3 worldPos, vec3 localOrigin) {
        return worldPos + calculateVerticalOffset(worldPos, localOrigin);
      }
    `)):r.code.add(l.Y`vec3 addVerticalOffset(vec3 worldPos, vec3 localOrigin) { return worldPos; }`)}const f=(0,o.Su)()},2288:(e,t,r)=>{r.d(t,{y:()=>y});var i=r(90124),o=r(80324),a=r(68828),n=r(70984),s=r(88196),l=r(60288),c=r(4636);function d(e,t){const r=t.output===o.mC.ObjectAndLayerIdColor,i=t.objectAndLayerIdColorInstanced;r&&(e.varyings.add("objectAndLayerIdColorVarying","vec4"),i?e.attributes.add(c.K.INSTANCEOBJECTANDLAYERIDCOLOR,"vec4"):e.attributes.add(c.K.OBJECTANDLAYERIDCOLOR,"vec4")),e.vertex.code.add(l.Y`
     void forwardObjectAndLayerIdColor() {
      ${r?i?l.Y`objectAndLayerIdColorVarying = instanceObjectAndLayerIdColor * 0.003921568627451;`:l.Y`objectAndLayerIdColorVarying = objectAndLayerIdColor * 0.003921568627451;`:l.Y``} }`),e.fragment.code.add(l.Y`
      void outputObjectAndLayerIdColor() {
        ${r?l.Y`fragColor = objectAndLayerIdColorVarying;`:l.Y``} }`)}var u=r(55164),h=r(60072),m=r(60512),f=r(54132);function p(e,t){switch(t.output){case o.mC.Shadow:case o.mC.ShadowHighlight:case o.mC.ShadowExcludeHighlight:e.fragment.include(m.K),e.fragment.code.add(l.Y`float _calculateFragDepth(const in float depth) {
const float SLOPE_SCALE = 2.0;
const float BIAS = 20.0 * .000015259;
float m = max(abs(dFdx(depth)), abs(dFdy(depth)));
return depth + SLOPE_SCALE * m + BIAS;
}
void outputDepth(float _linearDepth) {
fragColor = floatToRgba4(_calculateFragDepth(_linearDepth));
}`);break;case o.mC.LinearDepth:e.fragment.include(f.s),e.fragment.code.add(l.Y`void outputDepth(float _linearDepth) {
fragColor = float2rgba(_linearDepth);
}`)}}var v=r(37008),g=r(49916);const _=(0,v.WK)(1,1,0,1),x=(0,v.WK)(1,0,1,1);function T(e){e.fragment.uniforms.add(new g.W("depthTexture",((e,t)=>t.mainDepth))),e.fragment.constants.add("occludedHighlightFlag","vec4",_).add("unoccludedHighlightFlag","vec4",x),e.fragment.code.add(l.Y`void outputHighlight() {
float sceneDepth = float(texelFetch(depthTexture, ivec2(gl_FragCoord.xy), 0).x);
if (gl_FragCoord.z > sceneDepth + 5e-7) {
fragColor = occludedHighlightFlag;
} else {
fragColor = unoccludedHighlightFlag;
}
}`)}var E=r(36244),b=r(93904),S=r(95224),A=r(12260);function y(e,t){const{vertex:r,fragment:c}=e,m=t.hasColorTexture&&t.alphaDiscardMode!==A.OA.Opaque;switch(t.output){case o.mC.LinearDepth:case o.mC.Shadow:case o.mC.ShadowHighlight:case o.mC.ShadowExcludeHighlight:case o.mC.ObjectAndLayerIdColor:(0,S.IE)(r,t),e.include(n.w,t),e.include(u.I,t),e.include(E.a,t),e.include(p,t),e.include(a.A3,t),e.include(d,t),(0,i._J)(e),e.varyings.add("depth","float"),m&&c.uniforms.add(new g.W("tex",(e=>e.texture))),r.code.add(l.Y`void main(void) {
vpos = getVertexInLocalOriginSpace();
vpos = subtractOrigin(vpos);
vpos = addVerticalOffset(vpos, localOrigin);
gl_Position = transformPositionWithDepth(proj, view, vpos, nearFar, depth);
forwardTextureCoordinates();
forwardObjectAndLayerIdColor();
}`),e.include(b.Q,t),c.code.add(l.Y`
          void main(void) {
            discardBySlice(vpos);
            ${m?l.Y`
                    vec4 texColor = texture(tex, ${t.hasColorTextureTransform?l.Y`colorUV`:l.Y`vuv0`});
                    discardOrAdjustAlpha(texColor);`:""}
            ${t.output===o.mC.ObjectAndLayerIdColor?l.Y`outputObjectAndLayerIdColor();`:l.Y`outputDepth(depth);`}
          }
        `);break;case o.mC.Normal:{(0,S.IE)(r,t),e.include(n.w,t),e.include(s.k,t),e.include(h.u8,t),e.include(u.I,t),e.include(E.a,t),m&&c.uniforms.add(new g.W("tex",(e=>e.texture))),t.normalType===s.g.ScreenDerivative&&e.varyings.add("vPositionView","vec3");const i=t.normalType===s.g.Attribute||t.normalType===s.g.Compressed;r.code.add(l.Y`
          void main(void) {
            vpos = getVertexInLocalOriginSpace();

            ${i?l.Y`vNormalWorld = dpNormalView(vvLocalNormal(normalModel()));`:l.Y`
                  // Get vertex position in camera space for screen-space derivative normals
                  vPositionView = (view * vec4(vpos, 1.0)).xyz;
                `}
            vpos = subtractOrigin(vpos);
            vpos = addVerticalOffset(vpos, localOrigin);
            gl_Position = transformPosition(proj, view, vpos);
            forwardTextureCoordinates();
          }
        `),e.include(a.A3,t),e.include(b.Q,t),c.code.add(l.Y`
          void main() {
            discardBySlice(vpos);
            ${m?l.Y`
                    vec4 texColor = texture(tex, ${t.hasColorTextureTransform?l.Y`colorUV`:l.Y`vuv0`});
                    discardOrAdjustAlpha(texColor);`:""}

            ${t.normalType===s.g.ScreenDerivative?l.Y`vec3 normal = screenDerivativeNormal(vPositionView);`:l.Y`
                  vec3 normal = normalize(vNormalWorld);
                  if (gl_FrontFacing == false){
                    normal = -normal;
                  }`}
            fragColor = vec4(0.5 + 0.5 * normal, 1.0);
          }
        `);break}case o.mC.Highlight:(0,S.IE)(r,t),e.include(n.w,t),e.include(u.I,t),e.include(E.a,t),m&&c.uniforms.add(new g.W("tex",(e=>e.texture))),r.code.add(l.Y`void main(void) {
vpos = getVertexInLocalOriginSpace();
vpos = subtractOrigin(vpos);
vpos = addVerticalOffset(vpos, localOrigin);
gl_Position = transformPosition(proj, view, vpos);
forwardTextureCoordinates();
}`),e.include(a.A3,t),e.include(b.Q,t),e.include(T,t),c.code.add(l.Y`
          void main() {
            discardBySlice(vpos);
            ${m?l.Y`
                    vec4 texColor = texture(tex, ${t.hasColorTextureTransform?l.Y`colorUV`:l.Y`vuv0`});
                    discardOrAdjustAlpha(texColor);`:""}
            outputHighlight();
          }
        `)}}},22340:(e,t,r)=>{r.d(t,{E:()=>a,W:()=>n});var i=r(54132),o=r(60288);function a(e){e.include(i.s),e.code.add(o.Y`float linearDepthFromFloat(float depth, vec2 nearFar) {
return -(depth * (nearFar[1] - nearFar[0]) + nearFar[0]);
}
float linearDepthFromRGBA(vec4 depth, vec2 nearFar) {
return linearDepthFromFloat(rgba2float(depth), nearFar);
}
float linearDepthFromTexture(sampler2D depthTexture, vec2 uv, vec2 nearFar) {
ivec2 iuv = ivec2(uv * vec2(textureSize(depthTexture, 0)));
return linearDepthFromRGBA(texelFetch(depthTexture, iuv, 0), nearFar);
}`)}function n(e){e.code.add(o.Y`float linearizeDepth(float depth, vec2 nearFar) {
float depthNdc = depth * 2.0 - 1.0;
return (2.0 * nearFar[0] * nearFar[1]) / (depthNdc * (nearFar[1] - nearFar[0]) - (nearFar[1] + nearFar[0]));
}
float linearDepthFromTexture(sampler2D depthTexture, vec2 uv, vec2 nearFar) {
ivec2 iuv = ivec2(uv * vec2(textureSize(depthTexture, 0)));
float depth = texelFetch(depthTexture, iuv, 0).r;
return linearizeDepth(depth, nearFar);
}`)}},87836:(e,t,r)=>{r.d(t,{W:()=>p});var i=r(68612),o=r(85256),a=r(55164),n=r(33610),s=r(58472),l=r(48688),c=r(60288),d=r(11388),u=r(83028),h=r(49916),m=r(75040),f=r(4636);function p(e,t){const r=e.fragment;t.hasVertexTangents?(e.attributes.add(f.K.TANGENT,"vec4"),e.varyings.add("vTangent","vec4"),t.doubleSidedMode===s.J.WindingOrder?r.code.add(c.Y`mat3 computeTangentSpace(vec3 normal) {
float tangentHeadedness = gl_FrontFacing ? vTangent.w : -vTangent.w;
vec3 tangent = normalize(gl_FrontFacing ? vTangent.xyz : -vTangent.xyz);
vec3 bitangent = cross(normal, tangent) * tangentHeadedness;
return mat3(tangent, bitangent, normal);
}`):r.code.add(c.Y`mat3 computeTangentSpace(vec3 normal) {
float tangentHeadedness = vTangent.w;
vec3 tangent = normalize(vTangent.xyz);
vec3 bitangent = cross(normal, tangent) * tangentHeadedness;
return mat3(tangent, bitangent, normal);
}`)):r.code.add(c.Y`mat3 computeTangentSpace(vec3 normal, vec3 pos, vec2 st) {
vec3 Q1 = dFdx(pos);
vec3 Q2 = dFdy(pos);
vec2 stx = dFdx(st);
vec2 sty = dFdy(st);
float det = stx.t * sty.s - sty.t * stx.s;
vec3 T = stx.t * Q2 - sty.t * Q1;
T = T - normal * dot(normal, T);
T *= inversesqrt(max(dot(T,T), 1.e-10));
vec3 B = sign(det) * cross(normal, T);
return mat3(T, B, normal);
}`),t.textureCoordinateType!==a.i.None&&(e.include(n.C,t),r.uniforms.add(t.pbrTextureBindType===m.u.Pass?new h.W("normalTexture",(e=>e.textureNormal)):new u.y("normalTexture",(e=>e.textureNormal))),t.hasNormalTextureTransform&&(r.uniforms.add(new l.$("scale",(e=>e.scale??o.Qw))),r.uniforms.add(new d.q("normalTextureTransformMatrix",(e=>e.normalTextureTransformMatrix??i.IJ)))),r.code.add(c.Y`vec3 computeTextureNormal(mat3 tangentSpace, vec2 uv) {
vec3 rawNormal = textureLookup(normalTexture, uv).rgb * 2.0 - 1.0;`),t.hasNormalTextureTransform&&r.code.add(c.Y`mat3 normalTextureRotation = mat3(normalTextureTransformMatrix[0][0]/scale[0], normalTextureTransformMatrix[0][1]/scale[1], 0.0,
normalTextureTransformMatrix[1][0]/scale[0], normalTextureTransformMatrix[1][1]/scale[1], 0.0,
0.0, 0.0, 0.0 );
rawNormal.xy = (normalTextureRotation * vec3(rawNormal.x, rawNormal.y, 1.0)).xy;`),r.code.add(c.Y`return tangentSpace * rawNormal;
}`))}},23792:(e,t,r)=>{r.d(t,{i:()=>j});var i,o,a=r(60288),n=r(49916),s=r(41948),l=r(53648),c=r(70680),d=r(57896),u=r(82003),h=r(1580),m=(r(9456),r(66360),r(72052),r(12552)),f=r(37648);!function(e){e[e.RED=0]="RED",e[e.RG=1]="RG",e[e.RGBA4=2]="RGBA4",e[e.RGBA=3]="RGBA",e[e.RGBA_MIPMAP=4]="RGBA_MIPMAP",e[e.R16F=5]="R16F",e[e.RGBA16F=6]="RGBA16F"}(i||(i={})),function(e){e[e.DEPTH_STENCIL_TEXTURE=0]="DEPTH_STENCIL_TEXTURE",e[e.DEPTH16_BUFFER=1]="DEPTH16_BUFFER"}(o||(o={}));const p=5e5;var v=r(43780),g=r(80324);const _={required:[]},x=(g.mC.LinearDepth,g.mC.CompositeColor,g.mC.Highlight,{required:[g.mC.Depth,g.mC.Normal]});class T extends v.c{consumes(){return _}get usedMemory(){return 0}get isDecoration(){return!1}get running(){return!1}get materialReference(){return null}modify(e){}get numGeometries(){return 0}get hasOccludees(){return!1}queryRenderOccludedState(e){return!1}}class E extends T{}var b=r(74780),S=r(93316),A=r(63524),y=r(1492),C=r(52404),w=r(81472);class M extends S.O{initializeProgram(e){return new y.G(e.rctx,M.shader.get().build(),A.q)}initializePipeline(){return(0,w._w)({colorWrite:w.kp})}}M.shader=new b.O(C.S,(()=>r.e(6e3).then(r.bind(r,96e3))));var R=r(85256);class I extends a.a{constructor(){super(...arguments),this.projScale=1}}class O extends I{constructor(){super(...arguments),this.intensity=1}}class N extends a.a{}class P extends N{constructor(){super(...arguments),this.blurSize=(0,R.Su)()}}var L,D,F=r(81844);class U extends S.O{initializeProgram(e){return new y.G(e.rctx,U.shader.get().build(),A.q)}initializePipeline(){return(0,w._w)({colorWrite:w.kp})}}U.shader=new b.O(F.S,(()=>r.e(1739).then(r.bind(r,1739)))),r(27308),(D=L||(L={}))[D.Antialiasing=0]="Antialiasing",D[D.HighQualityTransparency=1]="HighQualityTransparency",D[D.HighResolutionVoxel=2]="HighResolutionVoxel",D[D.HighResolutionAtmosphere=3]="HighResolutionAtmosphere",D[D.SSAO=4]="SSAO",D[D.WaterReflection=5]="WaterReflection",D[D.HighResolutionShadows=6]="HighResolutionShadows",D[D.PhysicalPixelRendering=7]="PhysicalPixelRendering";var G=r(40328),B=r(77800),V=r(22184),z=r(51576);const Y=2;let H=class extends E{constructor(e){super(e),this._context=null,this._passParameters=new O,this._drawParameters=new P,this.produces=new Map([[G.y.SSAO,()=>this._produces()]])}_getCameraElevation(){return this._context?.renderContext.bindParameters.camera.relativeElevation??1/0}_produces(){return null!=this._enableTime&&null!=this._context&&this._getCameraElevation()<p}consumes(){return this._produces()?x:_}initializeRenderContext(e){this._context=e,this.addHandles([(0,d.aE)((()=>this.view.qualitySettings.ambientOcclusion||this._context?.isFeatureEnabled(L.SSAO)),(e=>e?this._enable():this._disable()),d.KG)])}uninitializeRenderContext(){this._disable(),this._context=null}_disable(){this._passParameters.noiseTexture=(0,c.yI)(this._passParameters.noiseTexture),this._enableTime=null}destroy(){this._disable()}_enable(){if(null!=this._enableTime||!this._context)return;const e=Uint8Array.from(atob("eXKEvZaUc66cjIKElE1jlJ6MjJ6Ufkl+jn2fcXp5jBx7c6KEflSGiXuXeW6OWs+tfqZ2Yot2Y7Zzfo2BhniEj3xoiXuXj4eGZpqEaHKDWjSMe7palFlzc3BziYOGlFVzg6Zzg7CUY5JrjFF7eYJ4jIKEcyyEonSXe7qUfqZ7j3xofqZ2c4R5lFZ5Y0WUbppoe1l2cIh2ezyUho+BcHN2cG6DbpqJhqp2e1GcezhrdldzjFGUcyxjc3aRjDyEc1h7Sl17c6aMjH92pb6Mjpd4dnqBjMOEhqZleIOBYzB7gYx+fnqGjJuEkWlwnCx7fGl+c4hjfGyRe5qMlNOMfnqGhIWHc6OMi4GDc6aMfqZuc6aMzqJzlKZ+lJ6Me3qRfoFue0WUhoR5UraEa6qMkXiPjMOMlJOGe7JrUqKMjK6MeYRzdod+Sl17boiPc6qEeYBlcIh2c1WEe7GDiWCDa0WMjEmMdod+Y0WcdntzhmN8WjyMjKJjiXtzgYxYaGd+a89zlEV7e2GJfnd+lF1rcK5zc4p5cHuBhL6EcXp5eYB7fnh8iX6HjIKEeaxuiYOGc66RfG2Ja5hzjlGMjEmMe9OEgXuPfHyGhPeEdl6JY02McGuMfnqGhFiMa3WJfnx2l4hwcG1uhmN8c0WMc39og1GBbrCEjE2EZY+JcIh2cIuGhIWHe0mEhIVrc09+gY5+eYBlnCyMhGCDl3drfmmMgX15aGd+gYx+fnuRfnhzY1SMsluJfnd+hm98WtNrcIuGh4SEj0qPdkqOjFF7jNNjdnqBgaqUjMt7boeBhnZ4jDR7c5pze4GGjEFrhLqMjHyMc0mUhKZze4WEa117kWlwbpqJjHZ2eX2Bc09zeId+e0V7WlF7jHJ2l72BfId8l3eBgXyBe897jGl7c66cgW+Xc76EjKNbgaSEjGx4fId8jFFjgZB8cG6DhlFziZhrcIh2fH6HgUqBgXiPY8dahGFzjEmMhEFre2dxhoBzc5SGfleGe6alc7aUeYBlhKqUdlp+cH5za4OEczxza0Gcc4J2jHZ5iXuXjH2Jh5yRjH2JcFx+hImBjH+MpddCl3dreZeJjIt8ZW18bm1zjoSEeIOBlF9oh3N7hlqBY4+UeYFwhLJjeYFwaGd+gUqBYxiEYot2fqZ2ondzhL6EYyiEY02Ea0VjgZB8doaGjHxoc66cjEGEiXuXiXWMiZhreHx8frGMe75rY02Ec5pzfnhzlEp4a3VzjM+EhFFza3mUY7Zza1V5e2iMfGyRcziEhDyEkXZ2Y4OBnCx7g5t2eyBjgV6EhEFrcIh2dod+c4Z+nJ5zjm15jEmUeYxijJp7nL6clIpjhoR5WrZraGd+fnuRa6pzlIiMg6ZzfHx5foh+eX1ufnB5eX1ufnB5aJt7UqKMjIh+e3aBfm5lbYSBhGFze6J4c39oc0mUc4Z+e0V7fKFVe0WEdoaGY02Ec4Z+Y02EZYWBfH6HgU1+gY5+hIWUgW+XjJ57ebWRhFVScHuBfJ6PhBx7WqJzlM+Ujpd4gHZziX6HjHmEgZN+lJt5boiPe2GJgX+GjIGJgHZzeaxufnB5hF2JtdN7jJ57hp57hK6ElFVzg6ZzbmiEbndzhIWHe3uJfoFue3qRhJd2j3xoc65zlE1jc3p8lE1jhniEgXJ7e657vZaUc3qBh52BhIF4aHKDa9drgY5+c52GWqZzbpqJe8tjnM+UhIeMfo2BfGl+hG1zSmmMjKJjZVaGgX15c1lze0mEp4OHa3mUhIWHhDyclJ6MeYOJkXiPc0VzhFiMlKaEboSJa5Jze41re3qRhn+HZYWBe0mEc4p5fnORbox5lEp4hGFjhGGEjJuEc1WEhLZjeHeGa7KlfHx2hLaMeX1ugY5+hIWHhKGPjMN7c1WEho1zhoBzZYx7fnhzlJt5exyUhFFziXtzfmmMa6qMYyiEiXxweV12kZSMeWqXSl17fnhzxmmMrVGEe1mcc4p5eHeGjK6MgY5+doaGa6pzlGV7g1qBh4KHkXiPeW6OaKqafqZ2eXZ5e1V7jGd7boSJc3BzhJd2e0mcYot2h1RoY8dahK6EQmWEWjx7e1l2lL6UgXyBdnR4eU9zc0VreX1umqaBhld7fo2Bc6KEc5Z+hDyEcIeBWtNrfHyGe5qMhMuMe5qMhEGEbVVupcNzg3aHhIF4boeBe0mEdlptc39ofFl5Y8uUlJOGiYt2UmGEcyxjjGx4jFF7a657ZYWBnElzhp57iXtrgZN+tfOEhIOBjE2HgU1+e8tjjKNbiWCDhE15gUqBgYN7fnqGc66ce9d7iYSBj0qPcG6DnGGcT3eGa6qMZY+JlIiMl4hwc3aRdnqBlGV7eHJ2hLZjfnuRhDyEeX6MSk17g6Z+c6aUjHmEhIF4gXyBc76EZW18fGl+fkl+jCxrhoVwhDyUhIqGlL2DlI6EhJd2tdN7eYORhEGMa2Faa6pzc3Bzc4R5lIRznM+UY9eMhDycc5Z+c4p5c4iGY117pb6MgXuPrbJafnx2eYOJeXZ5e657hDyEcziElKZjfoB5eHeGj4WRhGGEe6KGeX1utTStc76EhFGJnCyMa5hzfH6HnNeceYB7hmN8gYuMhIVrczSMgYF8h3N7c5pza5hzjJqEYIRdgYuMlL2DeYRzhGGEeX1uhLaEc4iGeZ1zdl6JhrVteX6Me2iMfm5lWqJzSpqEa6pzdnmchHx2c6OMhNdrhoR5g3aHczxzeW52gV6Ejm15frGMc0Vzc4Z+l3drfniJe+9rWq5rlF1rhGGEhoVwe9OEfoh+e7pac09+c3qBY0lrhDycdnp2lJ6MiYOGhGCDc3aRlL2DlJt5doaGdnp2gYF8gWeOjF2Uc4R5c5Z+jEmMe7KEc4mEeYJ4dmyBe0mcgXiPbqJ7eYB7fmGGiYSJjICGlF1reZ2PnElzbpqJfH6Hc39oe4WEc5eJhK6EhqyJc3qBgZB8c09+hEmEaHKDhFGJc5SGiXWMUpaEa89zc6OMnCyMiXtrho+Be5qMc7KEjJ57dmN+hKGPjICGbmiEe7prdod+hGCDdnmchBx7eX6MkXZ2hGGEa657hm98jFFjY5JreYOJgY2EjHZ2a295Y3FajJ6Mc1J+YzB7e4WBjF2Uc4R5eV12gYxzg1qBeId+c9OUc5pzjFFjgY5+hFiMlIaPhoR5lIpjjIKBlNdSe7KEeX2BfrGMhIqGc65zjE2UhK6EklZ+QmWEeziMWqZza3VzdnR4foh+gYF8n3iJiZhrnKp7gYF8eId+lJ6Me1lrcIuGjKJjhmN8c66MjFF7a6prjJ6UnJ5zezyUfruRWlF7nI5zfHyGe657h4SEe8tjhBx7jFFjc09+c39ojICMeZeJeXt+YzRzjHZ2c0WEcIeBeXZ5onSXkVR+gYJ+eYFwdldzgYF7eX2BjJ6UiXuXlE1jh4SEe1mchLJjc4Z+hqZ7eXZ5bm1zlL6Ue5p7iWeGhKqUY5pzjKJjcIeBe8t7gXyBYIRdlEp4a3mGnK6EfmmMZpqEfFl5gYxzjKZuhGFjhoKGhHx2fnx2eXuMe3aBiWeGvbKMe6KGa5hzYzB7gZOBlGV7hmN8hqZlYot2Y117a6pzc6KEfId8foB5rctrfneJfJ6PcHN2hFiMc5pzjH92c0VzgY2EcElzdmCBlFVzg1GBc65zY4OBboeBcHiBeYJ4ewxzfHx5lIRzlEmEnLKEbk1zfJ6PhmN8eYBljBiEnMOEiXxwezyUcIeBe76EdsKEeX2BdnR4jGWUrXWMjGd7fkl+j4WRlEGMa5Jzho+BhDyEfnqMeXt+g3aHlE1jczClhNN7ZW18eHx8hGFjZW18iXWMjKJjhH57gYuMcIuGWjyMe4ZtjJuExmmMj4WRdntzi4GDhFFzYIRdnGGcjJp7Y0F7e4WEkbCGiX57fnSHa657a6prhBCMe3Z+SmmMjH92eHJ2hK6EY1FzexhrvbKMnI5za4OEfnd+eXuMhImBe897hLaMjN+EfG+BeIOBhF1+eZeJi4GDkXZ2eXKEgZ6Ejpd4c2GHa1V5e5KUfqZuhCx7jKp7lLZrg11+hHx2hFWUoot2nI5zgbh5mo9zvZaUe3qRbqKMfqZ2kbCGhFiM"),(e=>e.charCodeAt(0))),t=new z.O;t.wrapMode=B.Qz.CLAMP_TO_EDGE,t.pixelFormat=B.Id.RGB,t.wrapMode=B.Qz.REPEAT,t.hasMipmap=!0,t.width=32,t.height=32,this._passParameters.noiseTexture=new V.w(this._context.renderContext.rctx,t,e),null==this._ssaoTechnique&&(this._ssaoTechnique=this._context.techniqueRepository.acquire(U)),null==this._blurTechnique&&(this._blurTechnique=this._context.techniqueRepository.acquire(M)),this._enableTime=(0,u.w$)(0),this._context?.requestRender()}renderNode(e,t,r){const o=e.bindParameters,a=r?.get("normals"),n=a?.getTexture(),s=a?.getTexture(B.GE);if(null==this._enableTime||null==this._context||!n||!s)return;if(!this._ssaoTechnique.compiled||!this._blurTechnique.compiled)return this._enableTime=e.time,void this._context.requestRender();0===this._enableTime&&(this._enableTime=e.time);const c=e.rctx,d=o.camera,u=this.view.qualitySettings.fadeDuration,h=d.relativeElevation,m=(0,l.qk)((p-h)/2e5,0,1),v=u>0?Math.min(u,e.time-this._enableTime)/u:1,g=v*m;this._passParameters.normalTexture=n,this._passParameters.depthTexture=s,this._passParameters.projScale=1/d.computeScreenPixelSizeAtDist(1),this._passParameters.intensity=4*W/(0,F.g)(d)**6*g;const _=d.fullViewport[2],x=d.fullViewport[3],T=Math.round(_/Y),E=Math.round(x/Y),b=this._context.fbos,S=b.acquire(_,x,"ssao input",i.RG);c.unbindTexture(S.fbo.colorTexture),c.bindFramebuffer(S.fbo),c.setViewport(0,0,_,x),c.bindTechnique(this._ssaoTechnique,o,this._passParameters,this._drawParameters),c.screen.draw();const A=b.acquire(T,E,"ssao blur",i.RED);c.unbindTexture(A.fbo.colorTexture),c.bindFramebuffer(A.fbo),this._drawParameters.colorTexture=S.getTexture(),(0,f.WO)(this._drawParameters.blurSize,0,Y/x),c.bindTechnique(this._blurTechnique,o,this._passParameters,this._drawParameters),c.setViewport(0,0,T,E),c.screen.draw(),S.release();const y=b.acquire(T,E,"ssao",i.RED);return c.unbindTexture(y.fbo.colorTexture),c.bindFramebuffer(y.fbo),c.setViewport(0,0,_,x),c.setClearColor(1,1,1,0),c.clear(B.sl.COLOR_BUFFER_BIT),this._drawParameters.colorTexture=A.getTexture(),(0,f.WO)(this._drawParameters.blurSize,Y/_,0),c.bindTechnique(this._blurTechnique,o,this._passParameters,this._drawParameters),c.setViewport(0,0,T,E),c.screen.draw(),c.setViewport4fv(d.fullViewport),A.release(),v<1&&this._context.requestRender(),y}};(0,s._)([(0,h.qq)({constructOnly:!0})],H.prototype,"view",void 0),(0,s._)([(0,h.qq)()],H.prototype,"_context",void 0),H=(0,s._)([(0,m.c)("geoscene.views.3d.webgl-engine.effects.ssao.SSAO")],H);const W=.5;function j(e,t){const r=e.fragment;t.receiveAmbientOcclusion?(r.uniforms.add(new n.W("ssaoTex",((e,t)=>t.ssao?.getTexture()))),r.constants.add("blurSizePixelsInverse","float",1/Y),r.code.add(a.Y`float evaluateAmbientOcclusionInverse() {
vec2 ssaoTextureSizeInverse = 1.0 / vec2(textureSize(ssaoTex, 0));
return texture(ssaoTex, gl_FragCoord.xy * blurSizePixelsInverse * ssaoTextureSizeInverse).r;
}
float evaluateAmbientOcclusion() {
return 1.0 - evaluateAmbientOcclusionInverse();
}`)):r.code.add(a.Y`float evaluateAmbientOcclusionInverse() { return 1.0; }
float evaluateAmbientOcclusion() { return 0.0; }`)}},57704:(e,t,r)=>{r.d(t,{kf:()=>C,I4:()=>A,oZ:()=>y});var i=r(44584),o=r(98472),a=r(62484),n=r(74784),s=r(37008),l=r(81832),c=r(56124),d=r(56192),u=r(60288);function h(e,t){const r=e.fragment,i=void 0!==t.lightingSphericalHarmonicsOrder?t.lightingSphericalHarmonicsOrder:2;0===i?(r.uniforms.add(new c.C("lightingAmbientSH0",((e,t)=>(0,o.s)(m,t.lighting.sh.r[0],t.lighting.sh.g[0],t.lighting.sh.b[0])))),r.code.add(u.Y`vec3 calculateAmbientIrradiance(vec3 normal, float ambientOcclusion) {
vec3 ambientLight = 0.282095 * lightingAmbientSH0;
return ambientLight * (1.0 - ambientOcclusion);
}`)):1===i?(r.uniforms.add(new d.U("lightingAmbientSH_R",((e,t)=>(0,n.s)(f,t.lighting.sh.r[0],t.lighting.sh.r[1],t.lighting.sh.r[2],t.lighting.sh.r[3]))),new d.U("lightingAmbientSH_G",((e,t)=>(0,n.s)(f,t.lighting.sh.g[0],t.lighting.sh.g[1],t.lighting.sh.g[2],t.lighting.sh.g[3]))),new d.U("lightingAmbientSH_B",((e,t)=>(0,n.s)(f,t.lighting.sh.b[0],t.lighting.sh.b[1],t.lighting.sh.b[2],t.lighting.sh.b[3])))),r.code.add(u.Y`vec3 calculateAmbientIrradiance(vec3 normal, float ambientOcclusion) {
vec4 sh0 = vec4(
0.282095,
0.488603 * normal.x,
0.488603 * normal.z,
0.488603 * normal.y
);
vec3 ambientLight = vec3(
dot(lightingAmbientSH_R, sh0),
dot(lightingAmbientSH_G, sh0),
dot(lightingAmbientSH_B, sh0)
);
return ambientLight * (1.0 - ambientOcclusion);
}`)):2===i&&(r.uniforms.add(new c.C("lightingAmbientSH0",((e,t)=>(0,o.s)(m,t.lighting.sh.r[0],t.lighting.sh.g[0],t.lighting.sh.b[0]))),new d.U("lightingAmbientSH_R1",((e,t)=>(0,n.s)(f,t.lighting.sh.r[1],t.lighting.sh.r[2],t.lighting.sh.r[3],t.lighting.sh.r[4]))),new d.U("lightingAmbientSH_G1",((e,t)=>(0,n.s)(f,t.lighting.sh.g[1],t.lighting.sh.g[2],t.lighting.sh.g[3],t.lighting.sh.g[4]))),new d.U("lightingAmbientSH_B1",((e,t)=>(0,n.s)(f,t.lighting.sh.b[1],t.lighting.sh.b[2],t.lighting.sh.b[3],t.lighting.sh.b[4]))),new d.U("lightingAmbientSH_R2",((e,t)=>(0,n.s)(f,t.lighting.sh.r[5],t.lighting.sh.r[6],t.lighting.sh.r[7],t.lighting.sh.r[8]))),new d.U("lightingAmbientSH_G2",((e,t)=>(0,n.s)(f,t.lighting.sh.g[5],t.lighting.sh.g[6],t.lighting.sh.g[7],t.lighting.sh.g[8]))),new d.U("lightingAmbientSH_B2",((e,t)=>(0,n.s)(f,t.lighting.sh.b[5],t.lighting.sh.b[6],t.lighting.sh.b[7],t.lighting.sh.b[8])))),r.code.add(u.Y`vec3 calculateAmbientIrradiance(vec3 normal, float ambientOcclusion) {
vec3 ambientLight = 0.282095 * lightingAmbientSH0;
vec4 sh1 = vec4(
0.488603 * normal.x,
0.488603 * normal.z,
0.488603 * normal.y,
1.092548 * normal.x * normal.y
);
vec4 sh2 = vec4(
1.092548 * normal.y * normal.z,
0.315392 * (3.0 * normal.z * normal.z - 1.0),
1.092548 * normal.x * normal.z,
0.546274 * (normal.x * normal.x - normal.y * normal.y)
);
ambientLight += vec3(
dot(lightingAmbientSH_R1, sh1),
dot(lightingAmbientSH_G1, sh1),
dot(lightingAmbientSH_B1, sh1)
);
ambientLight += vec3(
dot(lightingAmbientSH_R2, sh2),
dot(lightingAmbientSH_G2, sh2),
dot(lightingAmbientSH_B2, sh2)
);
return ambientLight * (1.0 - ambientOcclusion);
}`),t.pbrMode!==l.Ax.Normal&&t.pbrMode!==l.Ax.Schematic||r.code.add(u.Y`const vec3 skyTransmittance = vec3(0.9, 0.9, 1.0);
vec3 calculateAmbientRadiance(float ambientOcclusion)
{
vec3 ambientLight = 1.2 * (0.282095 * lightingAmbientSH0) - 0.2;
return ambientLight *= (1.0 - ambientOcclusion) * skyTransmittance;
}`))}const m=(0,a.Su)(),f=(0,s.Su)();var p=r(23792),v=r(53672),g=r(36908),_=r(10060),x=r(7544),T=r(75040);class E extends x.C{constructor(e,t){super(e,"bool",T.u.Pass,((r,i,o)=>r.setUniform1b(e,t(i,o))))}}var b=r(95464);r(53648),(0,a.Su)();const S=.4;function A(e){e.constants.add("ambientBoostFactor","float",S)}function y(e){e.uniforms.add(new b.d("lightingGlobalFactor",((e,t)=>t.lighting.globalFactor)))}function C(e,t){const r=e.fragment;switch(e.include(p.i,t),t.pbrMode!==l.Ax.Disabled&&e.include(g.I,t),e.include(h,t),e.include(_.m),r.code.add(u.Y`
    const float GAMMA_SRGB = 2.1;
    const float INV_GAMMA_SRGB = 0.4761904;
    ${t.pbrMode===l.Ax.Disabled?"":"const vec3 GROUND_REFLECTANCE = vec3(0.2);"}
  `),A(r),y(r),(0,v.OB)(r),r.code.add(u.Y`
    float additionalDirectedAmbientLight(vec3 vPosWorld) {
      float vndl = dot(${t.spherical?u.Y`normalize(vPosWorld)`:u.Y`vec3(0.0, 0.0, 1.0)`}, mainLightDirection);
      return smoothstep(0.0, 1.0, clamp(vndl * 2.5, 0.0, 1.0));
    }
  `),(0,v.o1)(r),r.code.add(u.Y`vec3 evaluateAdditionalLighting(float ambientOcclusion, vec3 vPosWorld) {
float additionalAmbientScale = additionalDirectedAmbientLight(vPosWorld);
return (1.0 - ambientOcclusion) * additionalAmbientScale * ambientBoostFactor * lightingGlobalFactor * mainLightIntensity;
}`),t.pbrMode){case l.Ax.Disabled:case l.Ax.WaterOnIntegratedMesh:case l.Ax.Water:e.include(v.me),r.code.add(u.Y`vec3 evaluateSceneLighting(vec3 normalWorld, vec3 albedo, float shadow, float ssao, vec3 additionalLight)
{
vec3 mainLighting = evaluateMainLighting(normalWorld, shadow);
vec3 ambientLighting = calculateAmbientIrradiance(normalWorld, ssao);
vec3 albedoLinear = pow(albedo, vec3(GAMMA_SRGB));
vec3 totalLight = mainLighting + ambientLighting + additionalLight;
totalLight = min(totalLight, vec3(PI));
vec3 outColor = vec3((albedoLinear / PI) * totalLight);
return pow(outColor, vec3(INV_GAMMA_SRGB));
}`);break;case l.Ax.Normal:case l.Ax.Schematic:r.code.add(u.Y`const float fillLightIntensity = 0.25;
const float horizonLightDiffusion = 0.4;
const float additionalAmbientIrradianceFactor = 0.02;
vec3 evaluateSceneLightingPBR(vec3 normal, vec3 albedo, float shadow, float ssao, vec3 additionalLight, vec3 viewDir, vec3 normalGround, vec3 mrr, vec3 _emission, float additionalAmbientIrradiance)
{
vec3 viewDirection = -viewDir;
vec3 h = normalize(viewDirection + mainLightDirection);
PBRShadingInfo inputs;
inputs.NdotL = clamp(dot(normal, mainLightDirection), 0.001, 1.0);
inputs.NdotV = clamp(abs(dot(normal, viewDirection)), 0.001, 1.0);
inputs.NdotH = clamp(dot(normal, h), 0.0, 1.0);
inputs.VdotH = clamp(dot(viewDirection, h), 0.0, 1.0);
inputs.NdotNG = clamp(dot(normal, normalGround), -1.0, 1.0);
vec3 reflectedView = normalize(reflect(viewDirection, normal));
inputs.RdotNG = clamp(dot(reflectedView, normalGround), -1.0, 1.0);
inputs.albedoLinear = pow(albedo, vec3(GAMMA_SRGB));
inputs.ssao = ssao;
inputs.metalness = mrr[0];
inputs.roughness = clamp(mrr[1] * mrr[1], 0.001, 0.99);`),r.code.add(u.Y`inputs.f0 = (0.16 * mrr[2] * mrr[2]) * (1.0 - inputs.metalness) + inputs.albedoLinear * inputs.metalness;
inputs.f90 = vec3(clamp(dot(inputs.f0, vec3(50.0 * 0.33)), 0.0, 1.0));
inputs.diffuseColor = inputs.albedoLinear * (vec3(1.0) - inputs.f0) * (1.0 - inputs.metalness);`),t.useFillLights?r.uniforms.add(new E("hasFillLights",((e,t)=>t.enableFillLights))):r.constants.add("hasFillLights","bool",!1),r.code.add(u.Y`vec3 ambientDir = vec3(5.0 * normalGround[1] - normalGround[0] * normalGround[2], - 5.0 * normalGround[0] - normalGround[2] * normalGround[1], normalGround[1] * normalGround[1] + normalGround[0] * normalGround[0]);
ambientDir = ambientDir != vec3(0.0) ? normalize(ambientDir) : normalize(vec3(5.0, -1.0, 0.0));
inputs.NdotAmbDir = hasFillLights ? abs(dot(normal, ambientDir)) : 1.0;
vec3 mainLightIrradianceComponent = inputs.NdotL * (1.0 - shadow) * mainLightIntensity;
vec3 fillLightsIrradianceComponent = inputs.NdotAmbDir * mainLightIntensity * fillLightIntensity;
vec3 ambientLightIrradianceComponent = calculateAmbientIrradiance(normal, ssao) + additionalLight;
inputs.skyIrradianceToSurface = ambientLightIrradianceComponent + mainLightIrradianceComponent + fillLightsIrradianceComponent ;
inputs.groundIrradianceToSurface = GROUND_REFLECTANCE * ambientLightIrradianceComponent + mainLightIrradianceComponent + fillLightsIrradianceComponent ;`),r.uniforms.add(new b.d("lightingSpecularStrength",((e,t)=>t.lighting.mainLight.specularStrength)),new b.d("lightingEnvironmentStrength",((e,t)=>t.lighting.mainLight.environmentStrength))),r.code.add(u.Y`vec3 horizonRingDir = inputs.RdotNG * normalGround - reflectedView;
vec3 horizonRingH = normalize(viewDirection + horizonRingDir);
inputs.NdotH_Horizon = dot(normal, horizonRingH);
vec3 mainLightRadianceComponent = lightingSpecularStrength * normalDistribution(inputs.NdotH, inputs.roughness) * mainLightIntensity * (1.0 - shadow);
vec3 horizonLightRadianceComponent = lightingEnvironmentStrength * normalDistribution(inputs.NdotH_Horizon, min(inputs.roughness + horizonLightDiffusion, 1.0)) * mainLightIntensity * fillLightIntensity;
vec3 ambientLightRadianceComponent = lightingEnvironmentStrength * calculateAmbientRadiance(ssao) + additionalLight;
float normalDirectionModifier = mix(1., min(mix(0.1, 2.0, (inputs.NdotNG + 1.) * 0.5), 1.0), clamp(inputs.roughness * 5.0, 0.0 , 1.0));
inputs.skyRadianceToSurface = (ambientLightRadianceComponent + horizonLightRadianceComponent) * normalDirectionModifier + mainLightRadianceComponent;
inputs.groundRadianceToSurface = 0.5 * GROUND_REFLECTANCE * (ambientLightRadianceComponent + horizonLightRadianceComponent) * normalDirectionModifier + mainLightRadianceComponent;
inputs.averageAmbientRadiance = ambientLightIrradianceComponent[1] * (1.0 + GROUND_REFLECTANCE[1]);`),r.code.add(u.Y`
        vec3 reflectedColorComponent = evaluateEnvironmentIllumination(inputs);
        vec3 additionalMaterialReflectanceComponent = inputs.albedoLinear * additionalAmbientIrradiance;
        vec3 emissionComponent = _emission == vec3(0.0) ? _emission : pow(_emission, vec3(GAMMA_SRGB));
        vec3 outColorLinear = reflectedColorComponent + additionalMaterialReflectanceComponent + emissionComponent;
        ${t.pbrMode!==l.Ax.Schematic||t.hasColorTexture?u.Y`vec3 outColor = pow(blackLevelSoftCompression(outColorLinear, inputs), vec3(INV_GAMMA_SRGB));`:u.Y`vec3 outColor = pow(max(vec3(0.0), outColorLinear - 0.005 * inputs.averageAmbientRadiance), vec3(INV_GAMMA_SRGB));`}
        return outColor;
      }
    `);break;case l.Ax.Simplified:case l.Ax.TerrainWithWater:e.include(v.me),r.code.add(u.Y`const float roughnessTerrain = 0.5;
const float specularityTerrain = 0.5;
const vec3 fresnelReflectionTerrain = vec3(0.04);
vec3 evaluatePBRSimplifiedLighting(vec3 n, vec3 c, float shadow, float ssao, vec3 al, vec3 vd, vec3 nup) {
vec3 viewDirection = -vd;
vec3 h = normalize(viewDirection + mainLightDirection);
float NdotL = clamp(dot(n, mainLightDirection), 0.001, 1.0);
float NdotV = clamp(abs(dot(n, viewDirection)), 0.001, 1.0);
float NdotH = clamp(dot(n, h), 0.0, 1.0);
float NdotNG = clamp(dot(n, nup), -1.0, 1.0);
vec3 albedoLinear = pow(c, vec3(GAMMA_SRGB));
float lightness = 0.3 * albedoLinear[0] + 0.5 * albedoLinear[1] + 0.2 * albedoLinear[2];
vec3 f0 = (0.85 * lightness + 0.15) * fresnelReflectionTerrain;
vec3 f90 =  vec3(clamp(dot(f0, vec3(50.0 * 0.33)), 0.0, 1.0));
vec3 mainLightIrradianceComponent = (1. - shadow) * NdotL * mainLightIntensity;
vec3 ambientLightIrradianceComponent = calculateAmbientIrradiance(n, ssao) + al;
vec3 ambientSky = ambientLightIrradianceComponent + mainLightIrradianceComponent;
vec3 indirectDiffuse = ((1.0 - NdotNG) * mainLightIrradianceComponent + (1.0 + NdotNG ) * ambientSky) * 0.5;
vec3 outDiffColor = albedoLinear * (1.0 - f0) * indirectDiffuse / PI;
vec3 mainLightRadianceComponent = normalDistribution(NdotH, roughnessTerrain) * mainLightIntensity;
vec2 dfg = prefilteredDFGAnalytical(roughnessTerrain, NdotV);
vec3 specularColor = f0 * dfg.x + f90 * dfg.y;
vec3 specularComponent = specularityTerrain * specularColor * mainLightRadianceComponent;
vec3 outColorLinear = outDiffColor + specularComponent;
vec3 outColor = pow(outColorLinear, vec3(INV_GAMMA_SRGB));
return outColor;
}`);break;default:(0,i.aM)(t.pbrMode);case l.Ax.COUNT:}}(0,a.Su)()},53672:(e,t,r)=>{r.d(t,{OB:()=>a,me:()=>s,o1:()=>n});var i=r(56124),o=r(60288);function a(e){e.uniforms.add(new i.C("mainLightDirection",((e,t)=>t.lighting.mainLight.direction)))}function n(e){e.uniforms.add(new i.C("mainLightIntensity",((e,t)=>t.lighting.mainLight.intensity)))}function s(e){a(e.fragment),n(e.fragment),e.fragment.code.add(o.Y`vec3 evaluateMainLighting(vec3 normal_global, float shadowing) {
float dotVal = clamp(dot(normal_global, mainLightDirection), 0.0, 1.0);
return mainLightIntensity * ((1.0 - shadowing) * dotVal);
}`)}},50143:(e,t,r)=>{r.d(t,{_:()=>s});var i=r(22340),o=r(48688),a=r(60288),n=r(49916);function s(e,t){if(!t.multipassEnabled)return;e.fragment.include(i.E),e.fragment.uniforms.add(new n.W("terrainDepthTexture",((e,t)=>t.multipassTerrain.linearDepth?.getTexture())),new o.$("nearFar",((e,t)=>t.camera.nearFar)));const r=t.occlusionPass;e.fragment.code.add(a.Y`
   ${r?"bool":"void"} terrainDepthTest(float fragmentDepth) {
      vec4 data = texelFetch(terrainDepthTexture, ivec2(gl_FragCoord.xy), 0);
      float linearDepth = linearDepthFromRGBA(data, nearFar);
      ${r?a.Y`return fragmentDepth < linearDepth && data != vec4(0.0, 0.0, 0.0, 1.0);`:a.Y`
          if(fragmentDepth ${t.cullAboveGround?">":"<="} linearDepth){
            discard;
          }`}
    }`)}},58472:(e,t,r)=>{r.d(t,{J:()=>i,u:()=>s});var i,o,a=r(44584),n=r(60288);function s(e,t){const r=e.fragment;switch(r.code.add(n.Y`struct ShadingNormalParameters {
vec3 normalView;
vec3 viewDirection;
} shadingParams;`),t.doubleSidedMode){case i.None:r.code.add(n.Y`vec3 shadingNormal(ShadingNormalParameters params) {
return normalize(params.normalView);
}`);break;case i.View:r.code.add(n.Y`vec3 shadingNormal(ShadingNormalParameters params) {
return dot(params.normalView, params.viewDirection) > 0.0 ? normalize(-params.normalView) : normalize(params.normalView);
}`);break;case i.WindingOrder:r.code.add(n.Y`vec3 shadingNormal(ShadingNormalParameters params) {
return gl_FrontFacing ? normalize(params.normalView) : normalize(-params.normalView);
}`);break;default:(0,a.aM)(t.doubleSidedMode);case i.COUNT:}}(o=i||(i={}))[o.None=0]="None",o[o.View=1]="View",o[o.WindingOrder=2]="WindingOrder",o[o.COUNT=3]="COUNT"},36908:(e,t,r)=>{r.d(t,{I:()=>s});var i=r(60288);function o(e){const t=e.fragment.code;t.add(i.Y`vec3 evaluateDiffuseIlluminationHemisphere(vec3 ambientGround, vec3 ambientSky, float NdotNG)
{
return ((1.0 - NdotNG) * ambientGround + (1.0 + NdotNG) * ambientSky) * 0.5;
}`),t.add(i.Y`float integratedRadiance(float cosTheta2, float roughness)
{
return (cosTheta2 - 1.0) / (cosTheta2 * (1.0 - roughness * roughness) - 1.0);
}`),t.add(i.Y`vec3 evaluateSpecularIlluminationHemisphere(vec3 ambientGround, vec3 ambientSky, float RdotNG, float roughness)
{
float cosTheta2 = 1.0 - RdotNG * RdotNG;
float intRadTheta = integratedRadiance(cosTheta2, roughness);
float ground = RdotNG < 0.0 ? 1.0 - intRadTheta : 1.0 + intRadTheta;
float sky = 2.0 - ground;
return (ground * ambientGround + sky * ambientSky) * 0.5;
}`)}var a=r(81832),n=r(10060);function s(e,t){const r=e.fragment.code;e.include(n.m),t.pbrMode!==a.Ax.Normal&&t.pbrMode!==a.Ax.Schematic&&t.pbrMode!==a.Ax.Simplified&&t.pbrMode!==a.Ax.TerrainWithWater||(r.add(i.Y`float normalDistribution(float NdotH, float roughness)
{
float a = NdotH * roughness;
float b = roughness / (1.0 - NdotH * NdotH + a * a);
return b * b * INV_PI;
}`),r.add(i.Y`const vec4 c0 = vec4(-1.0, -0.0275, -0.572,  0.022);
const vec4 c1 = vec4( 1.0,  0.0425,  1.040, -0.040);
const vec2 c2 = vec2(-1.04, 1.04);
vec2 prefilteredDFGAnalytical(float roughness, float NdotV) {
vec4 r = roughness * c0 + c1;
float a004 = min(r.x * r.x, exp2(-9.28 * NdotV)) * r.x + r.y;
return c2 * a004 + r.zw;
}`)),t.pbrMode!==a.Ax.Normal&&t.pbrMode!==a.Ax.Schematic||(e.include(o),r.add(i.Y`struct PBRShadingInfo
{
float NdotL;
float NdotV;
float NdotH;
float VdotH;
float LdotH;
float NdotNG;
float RdotNG;
float NdotAmbDir;
float NdotH_Horizon;
vec3 skyRadianceToSurface;
vec3 groundRadianceToSurface;
vec3 skyIrradianceToSurface;
vec3 groundIrradianceToSurface;
float averageAmbientRadiance;
float ssao;
vec3 albedoLinear;
vec3 f0;
vec3 f90;
vec3 diffuseColor;
float metalness;
float roughness;
};`),r.add(i.Y`vec3 evaluateEnvironmentIllumination(PBRShadingInfo inputs) {
vec3 indirectDiffuse = evaluateDiffuseIlluminationHemisphere(inputs.groundIrradianceToSurface, inputs.skyIrradianceToSurface, inputs.NdotNG);
vec3 indirectSpecular = evaluateSpecularIlluminationHemisphere(inputs.groundRadianceToSurface, inputs.skyRadianceToSurface, inputs.RdotNG, inputs.roughness);
vec3 diffuseComponent = inputs.diffuseColor * indirectDiffuse * INV_PI;
vec2 dfg = prefilteredDFGAnalytical(inputs.roughness, inputs.NdotV);
vec3 specularColor = inputs.f0 * dfg.x + inputs.f90 * dfg.y;
vec3 specularComponent = specularColor * indirectSpecular;
return (diffuseComponent + specularComponent);
}`),r.add(i.Y`float gamutMapChanel(float x, vec2 p){
return (x < p.x) ? mix(0.0, p.y, x/p.x) : mix(p.y, 1.0, (x - p.x) / (1.0 - p.x) );
}`),r.add(i.Y`vec3 blackLevelSoftCompression(vec3 inColor, PBRShadingInfo inputs){
vec3 outColor;
vec2 p = vec2(0.02 * (inputs.averageAmbientRadiance), 0.0075 * (inputs.averageAmbientRadiance));
outColor.x = gamutMapChanel(inColor.x, p) ;
outColor.y = gamutMapChanel(inColor.y, p) ;
outColor.z = gamutMapChanel(inColor.z, p) ;
return outColor;
}`))}},81832:(e,t,r)=>{r.d(t,{Ax:()=>i,G4:()=>h});var i,o,a=r(33610),n=r(80645),s=r(56124),l=r(60288),c=r(83028),d=r(49916),u=r(75040);function h(e,t){const r=e.fragment,o=t.hasMetallicRoughnessTexture||t.hasEmissionTexture||t.hasOcclusionTexture;if(t.pbrMode===i.Normal&&o&&e.include(a.C,t),t.pbrMode!==i.Schematic)if(t.pbrMode!==i.Disabled){if(t.pbrMode===i.Normal){r.code.add(l.Y`vec3 mrr;
vec3 emission;
float occlusion;`);const e=t.pbrTextureBindType;t.hasMetallicRoughnessTexture&&(r.uniforms.add(e===u.u.Pass?new d.W("texMetallicRoughness",(e=>e.textureMetallicRoughness)):new c.y("texMetallicRoughness",(e=>e.textureMetallicRoughness))),r.code.add(l.Y`void applyMetallnessAndRoughness(vec2 uv) {
vec3 metallicRoughness = textureLookup(texMetallicRoughness, uv).rgb;
mrr[0] *= metallicRoughness.b;
mrr[1] *= metallicRoughness.g;
}`)),t.hasEmissionTexture&&(r.uniforms.add(e===u.u.Pass?new d.W("texEmission",(e=>e.textureEmissive)):new c.y("texEmission",(e=>e.textureEmissive))),r.code.add(l.Y`void applyEmission(vec2 uv) {
emission *= textureLookup(texEmission, uv).rgb;
}`)),t.hasOcclusionTexture?(r.uniforms.add(e===u.u.Pass?new d.W("texOcclusion",(e=>e.textureOcclusion)):new c.y("texOcclusion",(e=>e.textureOcclusion))),r.code.add(l.Y`void applyOcclusion(vec2 uv) {
occlusion *= textureLookup(texOcclusion, uv).r;
}
float getBakedOcclusion() {
return occlusion;
}`)):r.code.add(l.Y`float getBakedOcclusion() { return 1.0; }`),e===u.u.Pass?r.uniforms.add(new s.C("emissionFactor",(e=>e.emissiveFactor)),new s.C("mrrFactors",(e=>e.mrrFactors))):r.uniforms.add(new n.m("emissionFactor",(e=>e.emissiveFactor)),new n.m("mrrFactors",(e=>e.mrrFactors))),r.code.add(l.Y`
    void applyPBRFactors() {
      mrr = mrrFactors;
      emission = emissionFactor;
      occlusion = 1.0;

      ${t.hasMetallicRoughnessTexture?l.Y`applyMetallnessAndRoughness(${t.hasMetallicRoughnessTextureTransform?l.Y`metallicRoughnessUV`:"vuv0"});`:""}

      ${t.hasEmissionTexture?l.Y`applyEmission(${t.hasEmissiveTextureTransform?l.Y`emissiveUV`:"vuv0"});`:""}

      ${t.hasOcclusionTexture?l.Y`applyOcclusion(${t.hasOcclusionTextureTransform?l.Y`occlusionUV`:"vuv0"});`:""}
    }
  `)}}else r.code.add(l.Y`float getBakedOcclusion() { return 1.0; }`);else r.code.add(l.Y`vec3 mrr = vec3(0.0, 0.6, 0.2);
vec3 emission = vec3(0.0);
float occlusion = 1.0;
void applyPBRFactors() {}
float getBakedOcclusion() { return 1.0; }`)}r(10375),(o=i||(i={}))[o.Disabled=0]="Disabled",o[o.Normal=1]="Normal",o[o.Schematic=2]="Schematic",o[o.Water=3]="Water",o[o.WaterOnIntegratedMesh=4]="WaterOnIntegratedMesh",o[o.Simplified=5]="Simplified",o[o.TerrainWithWater=6]="TerrainWithWater",o[o.COUNT=7]="COUNT"},10060:(e,t,r)=>{r.d(t,{m:()=>o});var i=r(60288);function o(e){e.vertex.code.add(i.Y`const float PI = 3.141592653589793;`),e.fragment.code.add(i.Y`const float PI = 3.141592653589793;
const float LIGHT_NORMALIZATION = 1.0 / PI;
const float INV_PI = 0.3183098861837907;
const float HALF_PI = 1.570796326794897;`)}},64352:(e,t,r)=>{r.d(t,{WK:()=>m,i8:()=>h}),r(87104),r(62484);var i=r(60512),o=r(56192),a=r(5512),n=r(60288),s=r(7544),l=r(75040);class c extends s.C{constructor(e,t,r){super(e,"mat4",l.u.Draw,((r,i,o,a)=>r.setUniformMatrix4fv(e,t(i,o,a))),r)}}class d extends s.C{constructor(e,t,r){super(e,"mat4",l.u.Pass,((r,i,o)=>r.setUniformMatrix4fv(e,t(i,o))),r)}}var u=r(49916);function h(e,t){t.receiveShadows&&(e.fragment.uniforms.add(new d("shadowMapMatrix",((e,t)=>t.shadowMap.getShadowMapMatrices(e.origin)),4)),f(e))}function m(e,t){t.receiveShadows&&(e.fragment.uniforms.add(new c("shadowMapMatrix",((e,t)=>t.shadowMap.getShadowMapMatrices(e.origin)),4)),f(e))}function f(e){const t=e.fragment;t.include(i.K),t.uniforms.add(new u.W("shadowMap",((e,t)=>t.shadowMap.depthTexture)),new a.i("numCascades",((e,t)=>t.shadowMap.numCascades)),new o.U("cascadeDistances",((e,t)=>t.shadowMap.cascadeDistances))),t.code.add(n.Y`int chooseCascade(float depth, out mat4 mat) {
vec4 distance = cascadeDistances;
int i = depth < distance[1] ? 0 : depth < distance[2] ? 1 : depth < distance[3] ? 2 : 3;
mat = i == 0 ? shadowMapMatrix[0] : i == 1 ? shadowMapMatrix[1] : i == 2 ? shadowMapMatrix[2] : shadowMapMatrix[3];
return i;
}
vec3 lightSpacePosition(vec3 _vpos, mat4 mat) {
vec4 lv = mat * vec4(_vpos, 1.0);
lv.xy /= lv.w;
return 0.5 * lv.xyz + vec3(0.5);
}
vec2 cascadeCoordinates(int i, ivec2 textureSize, vec3 lvpos) {
float xScale = float(textureSize.y) / float(textureSize.x);
return vec2((float(i) + lvpos.x) * xScale, lvpos.y);
}
float readShadowMapDepth(ivec2 uv, sampler2D _depthTex) {
return rgba4ToFloat(texelFetch(_depthTex, uv, 0));
}
float posIsInShadow(ivec2 uv, vec3 lvpos, sampler2D _depthTex) {
return readShadowMapDepth(uv, _depthTex) < lvpos.z ? 1.0 : 0.0;
}
float filterShadow(vec2 uv, vec3 lvpos, ivec2 texSize, sampler2D _depthTex) {
vec2 st = fract(uv * vec2(texSize) + vec2(0.5));
ivec2 base = ivec2(uv * vec2(texSize) - vec2(0.5));
float s00 = posIsInShadow(ivec2(base.x, base.y), lvpos, _depthTex);
float s10 = posIsInShadow(ivec2(base.x + 1, base.y), lvpos, _depthTex);
float s11 = posIsInShadow(ivec2(base.x + 1, base.y + 1), lvpos, _depthTex);
float s01 = posIsInShadow(ivec2(base.x, base.y + 1), lvpos, _depthTex);
return mix(mix(s00, s10, st.x), mix(s01, s11, st.x), st.y);
}
float readShadowMap(const in vec3 _vpos, float _linearDepth) {
mat4 mat;
int i = chooseCascade(_linearDepth, mat);
if (i >= numCascades) { return 0.0; }
vec3 lvpos = lightSpacePosition(_vpos, mat);
if (lvpos.z >= 1.0 || lvpos.x < 0.0 || lvpos.x > 1.0 || lvpos.y < 0.0 || lvpos.y > 1.0) { return 0.0; }
ivec2 size = textureSize(shadowMap, 0);
vec2 uv = cascadeCoordinates(i, size, lvpos);
return filterShadow(uv, lvpos, size, shadowMap);
}`)}n.a,n.a},95128:(e,t,r)=>{r.d(t,{Qr:()=>c,UJ:()=>u,U_:()=>d,Wq:()=>l,wZ:()=>s});var i=r(68612),o=r(55164),a=r(60288),n=r(11388);function s(e,t){t.hasColorTextureTransform?(e.vertex.uniforms.add(new n.q("colorTextureTransformMatrix",(e=>e.colorTextureTransformMatrix??i.IJ))),e.varyings.add("colorUV","vec2"),e.vertex.code.add(a.Y`void forwardColorUV(){
colorUV = (colorTextureTransformMatrix * vec3(vuv0, 1.0)).xy;
}`)):e.vertex.code.add(a.Y`void forwardColorUV(){}`)}function l(e,t){t.hasNormalTextureTransform&&t.textureCoordinateType!==o.i.None?(e.vertex.uniforms.add(new n.q("normalTextureTransformMatrix",(e=>e.normalTextureTransformMatrix??i.IJ))),e.varyings.add("normalUV","vec2"),e.vertex.code.add(a.Y`void forwardNormalUV(){
normalUV = (normalTextureTransformMatrix * vec3(vuv0, 1.0)).xy;
}`)):e.vertex.code.add(a.Y`void forwardNormalUV(){}`)}function c(e,t){t.hasEmissionTextureTransform&&t.textureCoordinateType!==o.i.None?(e.vertex.uniforms.add(new n.q("emissiveTextureTransformMatrix",(e=>e.emissiveTextureTransformMatrix??i.IJ))),e.varyings.add("emissiveUV","vec2"),e.vertex.code.add(a.Y`void forwardEmissiveUV(){
emissiveUV = (emissiveTextureTransformMatrix * vec3(vuv0, 1.0)).xy;
}`)):e.vertex.code.add(a.Y`void forwardEmissiveUV(){}`)}function d(e,t){t.hasOcclusionTextureTransform&&t.textureCoordinateType!==o.i.None?(e.vertex.uniforms.add(new n.q("occlusionTextureTransformMatrix",(e=>e.occlusionTextureTransformMatrix??i.IJ))),e.varyings.add("occlusionUV","vec2"),e.vertex.code.add(a.Y`void forwardOcclusionUV(){
occlusionUV = (occlusionTextureTransformMatrix * vec3(vuv0, 1.0)).xy;
}`)):e.vertex.code.add(a.Y`void forwardOcclusionUV(){}`)}function u(e,t){t.hasMetallicRoughnessTextureTransform&&t.textureCoordinateType!==o.i.None?(e.vertex.uniforms.add(new n.q("metallicRoughnessTextureTransformMatrix",(e=>e.metallicRoughnessTextureTransformMatrix??i.IJ))),e.varyings.add("metallicRoughnessUV","vec2"),e.vertex.code.add(a.Y`void forwardMetallicRoughnessUV(){
metallicRoughnessUV = (metallicRoughnessTextureTransformMatrix * vec3(vuv0, 1.0)).xy;
}`)):e.vertex.code.add(a.Y`void forwardMetallicRoughnessUV(){}`)}},36244:(e,t,r)=>{r.d(t,{a:()=>b});var i=r(56124),o=r(7544),a=r(75040);class n extends o.C{constructor(e,t,r){super(e,"vec4",a.u.Pass,((r,i,o)=>r.setUniform4fv(e,t(i,o))),r)}}class s extends o.C{constructor(e,t,r){super(e,"float",a.u.Pass,((r,i,o)=>r.setUniform1fv(e,t(i,o))),r)}}var l=r(60288),c=r(11388),d=r(4636),u=(r(9456),r(53648),r(77556),r(68612),r(15008),r(87104)),h=(r(98472),r(62484)),m=(r(12780),r(41948)),f=r(43780),p=r(1580),v=(r(66360),r(72052),r(12552));let g=class extends f.c{constructor(){super(...arguments),this.SCENEVIEW_HITTEST_RETURN_INTERSECTOR=!1,this.DECONFLICTOR_SHOW_VISIBLE=!1,this.DECONFLICTOR_SHOW_INVISIBLE=!1,this.DECONFLICTOR_SHOW_GRID=!1,this.LABELS_SHOW_BORDER=!1,this.TEXT_SHOW_BASELINE=!1,this.TEXT_SHOW_BORDER=!1,this.OVERLAY_DRAW_DEBUG_TEXTURE=!1,this.OVERLAY_SHOW_CENTER=!1,this.SHOW_POI=!1,this.TESTS_DISABLE_OPTIMIZATIONS=!1,this.TESTS_DISABLE_FAST_UPDATES=!1,this.DRAW_MESH_GEOMETRY_NORMALS=!1,this.FEATURE_TILE_FETCH_SHOW_TILES=!1,this.FEATURE_TILE_TREE_SHOW_TILES=!1,this.TERRAIN_TILE_TREE_SHOW_TILES=!1,this.I3S_TREE_SHOW_TILES=!1,this.I3S_SHOW_MODIFICATIONS=!1,this.LOD_INSTANCE_RENDERER_DISABLE_UPDATES=!1,this.LOD_INSTANCE_RENDERER_COLORIZE_BY_LEVEL=!1,this.EDGES_SHOW_HIDDEN_TRANSPARENT_EDGES=!1,this.LINE_WIREFRAMES=!1}};var _,x,T;(0,m._)([(0,p.qq)()],g.prototype,"SCENEVIEW_HITTEST_RETURN_INTERSECTOR",void 0),(0,m._)([(0,p.qq)()],g.prototype,"DECONFLICTOR_SHOW_VISIBLE",void 0),(0,m._)([(0,p.qq)()],g.prototype,"DECONFLICTOR_SHOW_INVISIBLE",void 0),(0,m._)([(0,p.qq)()],g.prototype,"DECONFLICTOR_SHOW_GRID",void 0),(0,m._)([(0,p.qq)()],g.prototype,"LABELS_SHOW_BORDER",void 0),(0,m._)([(0,p.qq)()],g.prototype,"TEXT_SHOW_BASELINE",void 0),(0,m._)([(0,p.qq)()],g.prototype,"TEXT_SHOW_BORDER",void 0),(0,m._)([(0,p.qq)()],g.prototype,"OVERLAY_DRAW_DEBUG_TEXTURE",void 0),(0,m._)([(0,p.qq)()],g.prototype,"OVERLAY_SHOW_CENTER",void 0),(0,m._)([(0,p.qq)()],g.prototype,"SHOW_POI",void 0),(0,m._)([(0,p.qq)()],g.prototype,"TESTS_DISABLE_OPTIMIZATIONS",void 0),(0,m._)([(0,p.qq)()],g.prototype,"TESTS_DISABLE_FAST_UPDATES",void 0),(0,m._)([(0,p.qq)()],g.prototype,"DRAW_MESH_GEOMETRY_NORMALS",void 0),(0,m._)([(0,p.qq)()],g.prototype,"FEATURE_TILE_FETCH_SHOW_TILES",void 0),(0,m._)([(0,p.qq)()],g.prototype,"FEATURE_TILE_TREE_SHOW_TILES",void 0),(0,m._)([(0,p.qq)()],g.prototype,"TERRAIN_TILE_TREE_SHOW_TILES",void 0),(0,m._)([(0,p.qq)()],g.prototype,"I3S_TREE_SHOW_TILES",void 0),(0,m._)([(0,p.qq)()],g.prototype,"I3S_SHOW_MODIFICATIONS",void 0),(0,m._)([(0,p.qq)()],g.prototype,"LOD_INSTANCE_RENDERER_DISABLE_UPDATES",void 0),(0,m._)([(0,p.qq)()],g.prototype,"LOD_INSTANCE_RENDERER_COLORIZE_BY_LEVEL",void 0),(0,m._)([(0,p.qq)()],g.prototype,"EDGES_SHOW_HIDDEN_TRANSPARENT_EDGES",void 0),(0,m._)([(0,p.qq)()],g.prototype,"LINE_WIREFRAMES",void 0),g=(0,m._)([(0,v.c)("geoscene.views.3d.support.DebugFlags")],g),new g,(T=_||(_={}))[T.Undefined=0]="Undefined",T[T.DefinedSize=1]="DefinedSize",T[T.DefinedScale=2]="DefinedScale",function(e){e[e.Undefined=0]="Undefined",e[e.DefinedAngle=1]="DefinedAngle"}(x||(x={})),l.a,(0,u.Su)(),(0,h.Su)(),(0,u.Su)(),r(81048);const E=8;function b(e,t){const{vertex:r,attributes:o}=e;t.hasVvInstancing&&(t.vvSize||t.vvColor)&&o.add(d.K.INSTANCEFEATUREATTRIBUTE,"vec4"),t.vvSize?(r.uniforms.add(new i.C("vvSizeMinSize",(e=>e.vvSize.minSize))),r.uniforms.add(new i.C("vvSizeMaxSize",(e=>e.vvSize.maxSize))),r.uniforms.add(new i.C("vvSizeOffset",(e=>e.vvSize.offset))),r.uniforms.add(new i.C("vvSizeFactor",(e=>e.vvSize.factor))),r.uniforms.add(new c.q("vvSymbolRotationMatrix",(e=>e.vvSymbolRotationMatrix))),r.uniforms.add(new i.C("vvSymbolAnchor",(e=>e.vvSymbolAnchor))),r.code.add(l.Y`vec3 vvScale(vec4 _featureAttribute) {
return clamp(vvSizeOffset + _featureAttribute.x * vvSizeFactor, vvSizeMinSize, vvSizeMaxSize);
}
vec4 vvTransformPosition(vec3 position, vec4 _featureAttribute) {
return vec4(vvSymbolRotationMatrix * ( vvScale(_featureAttribute) * (position + vvSymbolAnchor)), 1.0);
}`),r.code.add(l.Y`
      const float eps = 1.192092896e-07;
      vec4 vvTransformNormal(vec3 _normal, vec4 _featureAttribute) {
        vec3 vvScale = clamp(vvSizeOffset + _featureAttribute.x * vvSizeFactor, vvSizeMinSize + eps, vvSizeMaxSize);
        return vec4(vvSymbolRotationMatrix * _normal / vvScale, 1.0);
      }

      ${t.hasVvInstancing?l.Y`
      vec4 vvLocalNormal(vec3 _normal) {
        return vvTransformNormal(_normal, instanceFeatureAttribute);
      }

      vec4 localPosition() {
        return vvTransformPosition(position, instanceFeatureAttribute);
      }`:""}
    `)):r.code.add(l.Y`vec4 localPosition() { return vec4(position, 1.0); }
vec4 vvLocalNormal(vec3 _normal) { return vec4(_normal, 1.0); }`),t.vvColor?(r.constants.add("vvColorNumber","int",E),r.uniforms.add(new s("vvColorValues",(e=>e.vvColor.values),E),new n("vvColorColors",(e=>e.vvColor.colors),E)),r.code.add(l.Y`
      vec4 interpolateVVColor(float value) {
        if (value <= vvColorValues[0]) {
          return vvColorColors[0];
        }

        for (int i = 1; i < vvColorNumber; ++i) {
          if (vvColorValues[i] >= value) {
            float f = (value - vvColorValues[i-1]) / (vvColorValues[i] - vvColorValues[i-1]);
            return mix(vvColorColors[i-1], vvColorColors[i], f);
          }
        }
        return vvColorColors[vvColorNumber - 1];
      }

      vec4 vvGetColor(vec4 featureAttribute) {
        return interpolateVVColor(featureAttribute.y);
      }

      ${t.hasVvInstancing?l.Y`
            vec4 vvColor() {
              return vvGetColor(instanceFeatureAttribute);
            }`:"vec4 vvColor() { return vec4(1.0); }"}
    `)):r.code.add(l.Y`vec4 vvColor() { return vec4(1.0); }`)}},74072:(e,t,r)=>{r.d(t,{A:()=>o,k:()=>i});const i=.1,o=.001},93904:(e,t,r)=>{r.d(t,{Q:()=>c});var i=r(74072),o=r(60288);function a(e){e.fragment.code.add(o.Y`
    #define discardOrAdjustAlpha(color) { if (color.a < ${o.Y.float(i.A)}) { discard; } }
  `)}var n=r(7544);r(75040),n.C;var s=r(95464),l=r(12260);function c(e,t){!function(e,t,r){const i=e.fragment;switch(t.alphaDiscardMode!==l.OA.Mask&&t.alphaDiscardMode!==l.OA.MaskBlend||i.uniforms.add(r),t.alphaDiscardMode){case l.OA.Blend:return e.include(a);case l.OA.Opaque:i.code.add(o.Y`void discardOrAdjustAlpha(inout vec4 color) {
color.a = 1.0;
}`);break;case l.OA.Mask:i.code.add(o.Y`#define discardOrAdjustAlpha(color) { if (color.a < textureAlphaCutoff) { discard; } else { color.a = 1.0; } }`);break;case l.OA.MaskBlend:e.fragment.code.add(o.Y`#define discardOrAdjustAlpha(color) { if (color.a < textureAlphaCutoff) { discard; } }`)}}(e,t,new s.d("textureAlphaCutoff",(e=>e.textureAlphaCutoff)))}},252:(e,t,r)=>{r.d(t,{ig:()=>d});var i=r(37648),o=r(85256),a=r(74784),n=r(37008),s=r(48688),l=r(56192),c=r(60288);function d(e){e.fragment.uniforms.add(new l.U("projInfo",((e,t)=>function(e){const t=e.projectionMatrix;return 0===t[11]?(0,a.s)(u,2/(e.fullWidth*t[0]),2/(e.fullHeight*t[5]),(1+t[12])/t[0],(1+t[13])/t[5]):(0,a.s)(u,-2/(e.fullWidth*t[0]),-2/(e.fullHeight*t[5]),(1-t[8])/t[0],(1-t[9])/t[5])}(t.camera)))),e.fragment.uniforms.add(new s.$("zScale",((e,t)=>function(e){return 0===e.projectionMatrix[11]?(0,i.WO)(h,0,1):(0,i.WO)(h,1,0)}(t.camera)))),e.fragment.code.add(c.Y`vec3 reconstructPosition(vec2 fragCoord, float depth) {
return vec3((fragCoord * projInfo.xy + projInfo.zw) * (zScale.x * depth + zScale.y), depth);
}`)}const u=(0,n.Su)(),h=(0,o.Su)()},36752:(e,t,r)=>{r.d(t,{O:()=>o});var i=r(60288);function o({code:e},t){t.doublePrecisionRequiresObfuscation?e.add(i.Y`vec3 dpPlusFrc(vec3 a, vec3 b) {
return mix(a, a + b, vec3(notEqual(b, vec3(0))));
}
vec3 dpMinusFrc(vec3 a, vec3 b) {
return mix(vec3(0), a - b, vec3(notEqual(a, b)));
}
vec3 dpAdd(vec3 hiA, vec3 loA, vec3 hiB, vec3 loB) {
vec3 t1 = dpPlusFrc(hiA, hiB);
vec3 e = dpMinusFrc(t1, hiA);
vec3 t2 = dpMinusFrc(hiB, e) + dpMinusFrc(hiA, dpMinusFrc(t1, e)) + loA + loB;
return t1 + t2;
}`):e.add(i.Y`vec3 dpAdd(vec3 hiA, vec3 loA, vec3 hiB, vec3 loB) {
vec3 t1 = hiA + hiB;
vec3 e = t1 - hiA;
vec3 t2 = ((hiB - e) + (hiA - (t1 - e))) + loA + loB;
return t1 + t2;
}`)}},29484:(e,t,r)=>{r.d(t,{u:()=>n});var i=r(78720),o=r(60288);function a(e){e.code.add(o.Y`vec4 premultiplyAlpha(vec4 v) {
return vec4(v.rgb * v.a, v.a);
}
vec3 rgb2hsv(vec3 c) {
vec4 K = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);
vec4 p = c.g < c.b ? vec4(c.bg, K.wz) : vec4(c.gb, K.xy);
vec4 q = c.r < p.x ? vec4(p.xyw, c.r) : vec4(c.r, p.yzx);
float d = q.x - min(q.w, q.y);
float e = 1.0e-10;
return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), min(d / (q.x + e), 1.0), q.x);
}
vec3 hsv2rgb(vec3 c) {
vec4 K = vec4(1.0, 2.0 / 3.0, 1.0 / 3.0, 3.0);
vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);
return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
}
float rgb2v(vec3 c) {
return max(c.x, max(c.y, c.z));
}`)}function n(e){e.include(a),e.code.add(o.Y`
    vec3 mixExternalColor(vec3 internalColor, vec3 textureColor, vec3 externalColor, int mode) {
      // workaround for artifacts in OSX using Intel Iris Pro
      vec3 internalMixed = internalColor * textureColor;
      vec3 allMixed = internalMixed * externalColor;

      if (mode == ${o.Y.int(i.KQ.Multiply)}) {
        return allMixed;
      }
      if (mode == ${o.Y.int(i.KQ.Ignore)}) {
        return internalMixed;
      }
      if (mode == ${o.Y.int(i.KQ.Replace)}) {
        return externalColor;
      }

      // tint (or something invalid)
      float vIn = rgb2v(internalMixed);
      vec3 hsvTint = rgb2hsv(externalColor);
      vec3 hsvOut = vec3(hsvTint.x, hsvTint.y, vIn * hsvTint.z);
      return hsv2rgb(hsvOut);
    }

    float mixExternalOpacity(float internalOpacity, float textureOpacity, float externalOpacity, int mode) {
      // workaround for artifacts in OSX using Intel Iris Pro
      float internalMixed = internalOpacity * textureOpacity;
      float allMixed = internalMixed * externalOpacity;

      if (mode == ${o.Y.int(i.KQ.Ignore)}) {
        return internalMixed;
      }
      if (mode == ${o.Y.int(i.KQ.Replace)}) {
        return externalOpacity;
      }

      // multiply or tint (or something invalid)
      return allMixed;
    }
  `)}},60512:(e,t,r)=>{r.d(t,{K:()=>o});var i=r(60288);function o(e){e.code.add(i.Y`const float MAX_RGBA4_FLOAT =
15.0 / 16.0 +
15.0 / 16.0 / 16.0 +
15.0 / 16.0 / 16.0 / 16.0 +
15.0 / 16.0 / 16.0 / 16.0 / 16.0;
const vec4 FIXED_POINT_FACTORS_RGBA4 = vec4(1.0, 16.0, 16.0 * 16.0, 16.0 * 16.0 * 16.0);
vec4 floatToRgba4(const float value) {
float valueInValidDomain = clamp(value, 0.0, MAX_RGBA4_FLOAT);
vec4 fixedPointU4 = floor(fract(valueInValidDomain * FIXED_POINT_FACTORS_RGBA4) * 16.0);
const float toU4AsFloat = 1.0 / 15.0;
return fixedPointU4 * toU4AsFloat;
}
const vec4 RGBA4_2_FLOAT_FACTORS = vec4(
15.0 / (16.0),
15.0 / (16.0 * 16.0),
15.0 / (16.0 * 16.0 * 16.0),
15.0 / (16.0 * 16.0 * 16.0 * 16.0)
);
float rgba4ToFloat(vec4 rgba) {
return dot(rgba, RGBA4_2_FLOAT_FACTORS);
}`)}},54132:(e,t,r)=>{r.d(t,{s:()=>o});var i=r(60288);function o(e){e.code.add(i.Y`const float MAX_RGBA_FLOAT =
255.0 / 256.0 +
255.0 / 256.0 / 256.0 +
255.0 / 256.0 / 256.0 / 256.0 +
255.0 / 256.0 / 256.0 / 256.0 / 256.0;
const vec4 FIXED_POINT_FACTORS = vec4(1.0, 256.0, 256.0 * 256.0, 256.0 * 256.0 * 256.0);
vec4 float2rgba(const float value) {
float valueInValidDomain = clamp(value, 0.0, MAX_RGBA_FLOAT);
vec4 fixedPointU8 = floor(fract(valueInValidDomain * FIXED_POINT_FACTORS) * 256.0);
const float toU8AsFloat = 1.0 / 255.0;
return fixedPointU8 * toU8AsFloat;
}
const vec4 RGBA_2_FLOAT_FACTORS = vec4(
255.0 / (256.0),
255.0 / (256.0 * 256.0),
255.0 / (256.0 * 256.0 * 256.0),
255.0 / (256.0 * 256.0 * 256.0 * 256.0)
);
float rgba2float(vec4 rgba) {
return dot(rgba, RGBA_2_FLOAT_FACTORS);
}`)}},95224:(e,t,r)=>{r.d(t,{E7:()=>m,IE:()=>f,CI:()=>g});var i=r(15008),o=r(87104),a=r(98472),n=r(62484),s=r(80645),l=r(56124),c=(r(95464),r(7544)),d=r(75040);class u extends c.C{constructor(e,t){super(e,"mat4",d.u.Draw,((r,i,o)=>r.setUniformMatrix4fv(e,t(i,o))))}}var h=r(47572);function m(e,t){t.instancedDoublePrecision?e.constants.add("cameraPosition","vec3",n.cX):e.uniforms.add(new s.m("cameraPosition",((e,t)=>(0,a.s)(v,t.camera.viewInverseTransposeMatrix[3]-e.origin[0],t.camera.viewInverseTransposeMatrix[7]-e.origin[1],t.camera.viewInverseTransposeMatrix[11]-e.origin[2]))))}function f(e,t){if(!t.instancedDoublePrecision)return void e.uniforms.add(new h.O("proj",((e,t)=>t.camera.projectionMatrix)),new u("view",((e,t)=>(0,i.GS)(p,t.camera.viewMatrix,e.origin))),new s.m("localOrigin",(e=>e.origin)));const r=e=>(0,a.s)(v,e.camera.viewInverseTransposeMatrix[3],e.camera.viewInverseTransposeMatrix[7],e.camera.viewInverseTransposeMatrix[11]);e.uniforms.add(new h.O("proj",((e,t)=>t.camera.projectionMatrix)),new h.O("view",((e,t)=>(0,i.GS)(p,t.camera.viewMatrix,r(t)))),new l.C("localOrigin",((e,t)=>r(t))))}const p=(0,o.Su)(),v=(0,n.Su)();function g(e){e.uniforms.add(new h.O("viewNormal",((e,t)=>t.camera.viewInverseTransposeMatrix)))}},79216:(e,t,r)=>{r.d(t,{O:()=>a});var i=r(7544),o=r(75040);class a extends i.C{constructor(e,t){super(e,"vec2",o.u.Draw,((r,i,o,a)=>r.setUniform2fv(e,t(i,o,a))))}}},48688:(e,t,r)=>{r.d(t,{$:()=>a});var i=r(7544),o=r(75040);class a extends i.C{constructor(e,t){super(e,"vec2",o.u.Pass,((r,i,o)=>r.setUniform2fv(e,t(i,o))))}}},80645:(e,t,r)=>{r.d(t,{m:()=>a});var i=r(7544),o=r(75040);class a extends i.C{constructor(e,t){super(e,"vec3",o.u.Draw,((r,i,o,a)=>r.setUniform3fv(e,t(i,o,a))))}}},56124:(e,t,r)=>{r.d(t,{C:()=>a});var i=r(7544),o=r(75040);class a extends i.C{constructor(e,t){super(e,"vec3",o.u.Pass,((r,i,o)=>r.setUniform3fv(e,t(i,o))))}}},56192:(e,t,r)=>{r.d(t,{U:()=>a});var i=r(7544),o=r(75040);class a extends i.C{constructor(e,t){super(e,"vec4",o.u.Pass,((r,i,o)=>r.setUniform4fv(e,t(i,o))))}}},95464:(e,t,r)=>{r.d(t,{d:()=>a});var i=r(7544),o=r(75040);class a extends i.C{constructor(e,t){super(e,"float",o.u.Pass,((r,i,o)=>r.setUniform1f(e,t(i,o))))}}},5512:(e,t,r)=>{r.d(t,{i:()=>a});var i=r(7544),o=r(75040);class a extends i.C{constructor(e,t){super(e,"int",o.u.Pass,((r,i,o)=>r.setUniform1i(e,t(i,o))))}}},44228:(e,t,r)=>{r.d(t,{o:()=>a});var i=r(7544),o=r(75040);class a extends i.C{constructor(e,t){super(e,"mat3",o.u.Draw,((r,i,o)=>r.setUniformMatrix3fv(e,t(i,o))))}}},11388:(e,t,r)=>{r.d(t,{q:()=>a});var i=r(7544),o=r(75040);class a extends i.C{constructor(e,t){super(e,"mat3",o.u.Pass,((r,i,o)=>r.setUniformMatrix3fv(e,t(i,o))))}}},47572:(e,t,r)=>{r.d(t,{O:()=>a});var i=r(7544),o=r(75040);class a extends i.C{constructor(e,t){super(e,"mat4",o.u.Pass,((r,i,o)=>r.setUniformMatrix4fv(e,t(i,o))))}}},14920:(e,t,r)=>{r.d(t,{i0:()=>c});var i=r(20744),o=r(66360),a=r(75040),n=r(90736);const s=()=>o.c.getLogger("geoscene.views.3d.webgl-engine.core.shaderModules.shaderBuilder");class l{constructor(){this._includedModules=new Map}include(e,t){if(this._includedModules.has(e)){const r=this._includedModules.get(e);if(r!==t){s().error("Shader module included multiple times with different configuration.");const t=new Set;for(const i of Object.keys(r))r[i]!==e[i]&&t.add(i);for(const i of Object.keys(e))r[i]!==e[i]&&t.add(i);t.forEach((t=>console.error(`  ${t}: current ${r[t]} new ${e[t]}`)))}}else this._includedModules.set(e,t),e(this.builder,t)}}class c extends l{constructor(){super(...arguments),this.vertex=new h,this.fragment=new h,this.attributes=new m,this.varyings=new f,this.extensions=new p,this.constants=new g,this.outputs=new v}get fragmentUniforms(){return this.fragment.uniforms.entries}get builder(){return this}generate(e){const t=this.extensions.generateSource(e),r=this.attributes.generateSource(e),i=this.varyings.generateSource(e),o="vertex"===e?this.vertex:this.fragment,a=o.uniforms.generateSource(),n=o.code.generateSource(),s="vertex"===e?x:_,l=this.constants.generateSource().concat(o.constants.generateSource()),c=this.outputs.generateSource(e);return`#version 300 es\n${t.join("\n")}\n\n${s}\n\n${l.join("\n")}\n\n${a.join("\n")}\n\n${r.join("\n")}\n\n${i.join("\n")}\n\n${c.join("\n")}\n\n${n.join("\n")}`}generateBindPass(e){const t=new Map;this.vertex.uniforms.entries.forEach((e=>{const r=e.bind[a.u.Pass];r&&t.set(e.name,r)})),this.fragment.uniforms.entries.forEach((e=>{const r=e.bind[a.u.Pass];r&&t.set(e.name,r)}));const r=Array.from(t.values()),i=r.length;return(t,o)=>{for(let a=0;a<i;++a)r[a](e,t,o)}}generateBindDraw(e){const t=new Map;this.vertex.uniforms.entries.forEach((e=>{const r=e.bind[a.u.Draw];r&&t.set(e.name,r)})),this.fragment.uniforms.entries.forEach((e=>{const r=e.bind[a.u.Draw];r&&t.set(e.name,r)}));const r=Array.from(t.values()),i=r.length;return(t,o,a)=>{for(let n=0;n<i;++n)r[n](e,t,o,a)}}}class d{constructor(){this._entries=new Map}add(...e){for(const t of e)this._add(t)}get(e){return this._entries.get(e)}_add(e){if(null!=e){if(this._entries.has(e.name)&&!this._entries.get(e.name).equals(e))throw new i.c(`Duplicate uniform name ${e.name} for different uniform type`);this._entries.set(e.name,e)}else s().error(`Trying to add null Uniform from ${(new Error).stack}.`)}generateSource(){return Array.from(this._entries.values()).map((e=>null!=e.arraySize?`uniform ${e.type} ${e.name}[${e.arraySize}];`:`uniform ${e.type} ${e.name};`))}get entries(){return Array.from(this._entries.values())}}class u{constructor(){this._entries=new Array}add(e){this._entries.push(e)}generateSource(){return this._entries}}class h extends l{constructor(){super(...arguments),this.uniforms=new d,this.code=new u,this.constants=new g}get builder(){return this}}class m{constructor(){this._entries=new Array}add(e,t){this._entries.push([e,t])}generateSource(e){return"fragment"===e?[]:this._entries.map((e=>`in ${e[1]} ${e[0]};`))}}class f{constructor(){this._entries=new Map}add(e,t){this._entries.has(e)&&(0,n.Uc)(this._entries.get(e)===t),this._entries.set(e,t)}generateSource(e){const t=new Array;return this._entries.forEach(((r,i)=>t.push("vertex"===e?`out ${r} ${i};`:`in ${r} ${i};`))),t}}class p{constructor(){this._entries=new Set}add(e){this._entries.add(e)}generateSource(e){const t="vertex"===e?p.ALLOWLIST_VERTEX:p.ALLOWLIST_FRAGMENT;return Array.from(this._entries).filter((e=>t.includes(e))).map((e=>`#extension ${e} : enable`))}}p.ALLOWLIST_FRAGMENT=["GL_EXT_shader_texture_lod","GL_OES_standard_derivatives"],p.ALLOWLIST_VERTEX=[];class v{constructor(){this._entries=new Map}add(e,t,r=0){const i=this._entries.get(r);i?(0,n.Uc)(i.name===e&&i.type===t,`Fragment shader output location ${r} occupied`):this._entries.set(r,{name:e,type:t})}generateSource(e){if("vertex"===e)return[];0===this._entries.size&&this._entries.set(0,{name:v.DEFAULT_NAME,type:v.DEFAULT_TYPE});const t=new Array;return this._entries.forEach(((e,r)=>t.push(`layout(location = ${r}) out ${e.type} ${e.name};`))),t}}v.DEFAULT_TYPE="vec4",v.DEFAULT_NAME="fragColor";class g{constructor(){this._entries=new Set}add(e,t,r){let i="ERROR_CONSTRUCTOR_STRING";switch(t){case"float":i=g._numberToFloatStr(r);break;case"int":i=g._numberToIntStr(r);break;case"bool":i=r.toString();break;case"vec2":i=`vec2(${g._numberToFloatStr(r[0])},                            ${g._numberToFloatStr(r[1])})`;break;case"vec3":i=`vec3(${g._numberToFloatStr(r[0])},                            ${g._numberToFloatStr(r[1])},                            ${g._numberToFloatStr(r[2])})`;break;case"vec4":i=`vec4(${g._numberToFloatStr(r[0])},                            ${g._numberToFloatStr(r[1])},                            ${g._numberToFloatStr(r[2])},                            ${g._numberToFloatStr(r[3])})`;break;case"ivec2":i=`ivec2(${g._numberToIntStr(r[0])},                             ${g._numberToIntStr(r[1])})`;break;case"ivec3":i=`ivec3(${g._numberToIntStr(r[0])},                             ${g._numberToIntStr(r[1])},                             ${g._numberToIntStr(r[2])})`;break;case"ivec4":i=`ivec4(${g._numberToIntStr(r[0])},                             ${g._numberToIntStr(r[1])},                             ${g._numberToIntStr(r[2])},                             ${g._numberToIntStr(r[3])})`;break;case"mat2":case"mat3":case"mat4":i=`${t}(${Array.prototype.map.call(r,(e=>g._numberToFloatStr(e))).join(", ")})`}return this._entries.add(`const ${t} ${e} = ${i};`),this}static _numberToIntStr(e){return e.toFixed(0)}static _numberToFloatStr(e){return Number.isInteger(e)?e.toFixed(1):e.toString()}generateSource(){return Array.from(this._entries)}}const _="#ifdef GL_FRAGMENT_PRECISION_HIGH\n  precision highp float;\n  precision highp sampler2D;\n#else\n  precision mediump float;\n  precision mediump sampler2D;\n#endif",x="precision highp float;\nprecision highp sampler2D;"},83028:(e,t,r)=>{r.d(t,{y:()=>a});var i=r(7544),o=r(75040);class a extends i.C{constructor(e,t){super(e,"sampler2D",o.u.Draw,((r,i,o)=>r.bindTexture(e,t(i,o))))}}},49916:(e,t,r)=>{r.d(t,{W:()=>a});var i=r(7544),o=r(75040);class a extends i.C{constructor(e,t){super(e,"sampler2D",o.u.Pass,((r,i,o)=>r.bindTexture(e,t(i,o))))}}},7544:(e,t,r)=>{r.d(t,{C:()=>o});var i=r(75040);class o{constructor(e,t,r,o,a=null){if(this.name=e,this.type=t,this.arraySize=a,this.bind={[i.u.Pass]:null,[i.u.Draw]:null},o)switch(r){case i.u.Pass:this.bind[i.u.Pass]=o;break;case i.u.Draw:this.bind[i.u.Draw]=o}}equals(e){return this.type===e.type&&this.name===e.name&&this.arraySize===e.arraySize}}},60288:(e,t,r)=>{r.d(t,{Y:()=>o,a:()=>i});const i=class{};function o(e,...t){let r="";for(let i=0;i<t.length;i++)r+=e[i]+t[i];return r+=e[e.length-1],r}var a;(a=o||(o={})).int=function(e){return Math.round(e).toString()},a.float=function(e){return e.toPrecision(8)}},75040:(e,t,r)=>{var i;r.d(t,{u:()=>i}),function(e){e[e.Pass=0]="Pass",e[e.Draw=1]="Draw"}(i||(i={}))},74780:(e,t,r)=>{r.d(t,{O:()=>i});class i{constructor(e,t){this._module=e,this._loadModule=t}get(){return this._module}async reload(){return this._module=await this._loadModule(),this._module}}},93316:(e,t,r)=>{r.d(t,{O:()=>a});var i=r(70680),o=r(77800);class a{constructor(e,t,r){this.release=r,this.initializeConfiguration(e,t),this._configuration=t.snapshot(),this._program=this.initializeProgram(e),this._pipeline=this.initializePipeline(e)}destroy(){this._program=(0,i.yI)(this._program),this._pipeline=this._configuration=null}reload(e){(0,i.yI)(this._program),this._program=this.initializeProgram(e),this._pipeline=this.initializePipeline(e)}get program(){return this._program}get compiled(){return this.program.compiled}get key(){return this._configuration.key}get configuration(){return this._configuration}ensureAttributeLocations(e){this.program.assertCompatibleVertexAttributeLocations(e)}get primitiveType(){return o.Uf.TRIANGLES}getPipeline(e,t,r){return this._pipeline}initializeConfiguration(e,t){}}},74912:(e,t,r)=>{r.d(t,{K:()=>o,y:()=>a});var i=r(60288);class o extends i.a{constructor(){super(),this._key="",this._keyDirty=!1,this._parameterBits=this._parameterBits?this._parameterBits.map((()=>0)):[],this._parameterNames||(this._parameterNames=[])}get key(){return this._keyDirty&&(this._keyDirty=!1,this._key=String.fromCharCode.apply(String,this._parameterBits)),this._key}snapshot(){const e=this._parameterNames,t={key:this.key};for(const r of e)t[r]=this[r];return t}}function a(e={}){return(t,r)=>{if(t._parameterNames=t._parameterNames??[],t._parameterNames.push(r),null!=e.constValue)Object.defineProperty(t,r,{get:()=>e.constValue});else{const i=t._parameterNames.length-1,o=e.count||2,a=Math.ceil(Math.log2(o)),n=t._parameterBits??[0];let s=0;for(;n[s]+a>16;)s++,s>=n.length&&n.push(0);t._parameterBits=n;const l=n[s],c=(1<<a)-1<<l;n[s]+=a,Object.defineProperty(t,r,{get(){return this[i]},set(e){if(this[i]!==e&&(this[i]=e,this._keyDirty=!0,this._parameterBits[s]=this._parameterBits[s]&~c|+e<<l&c,"number"!=typeof e&&"boolean"!=typeof e))throw new Error("Configuration value for "+r+" must be boolean or number, got "+typeof e)}})}}}},63524:(e,t,r)=>{r.d(t,{q:()=>o});var i=r(4636);const o=new Map([[i.K.POSITION,0],[i.K.NORMAL,1],[i.K.NORMALCOMPRESSED,1],[i.K.UV0,2],[i.K.COLOR,3],[i.K.COLORFEATUREATTRIBUTE,3],[i.K.SIZE,4],[i.K.TANGENT,4],[i.K.CENTEROFFSETANDDISTANCE,5],[i.K.SYMBOLCOLOR,5],[i.K.FEATUREATTRIBUTE,6],[i.K.INSTANCEFEATUREATTRIBUTE,6],[i.K.INSTANCECOLOR,7],[i.K.OBJECTANDLAYERIDCOLOR,7],[i.K.INSTANCEOBJECTANDLAYERIDCOLOR,7],[i.K.INSTANCEMODEL,8],[i.K.INSTANCEMODELNORMAL,12],[i.K.INSTANCEMODELORIGINHI,11],[i.K.INSTANCEMODELORIGINLO,15]])},10375:(e,t,r)=>{r.d(t,{Q:()=>l});var i=r(70680),o=r(10860),a=r(60288),n=r(12260);class s{constructor(e){this._material=e.material,this._techniqueRepository=e.techniqueRep,this._output=e.output}dispose(){this._techniqueRepository.release(this._technique)}get technique(){return this._technique}get _stippleTextureRepository(){return this._techniqueRepository.constructionContext.stippleTextureRepository}get _markerTextureRepository(){return this._techniqueRepository.constructionContext.markerTextureRepository}ensureTechnique(e,t){return this._technique=this._techniqueRepository.releaseAndAcquire(e,this._material.getConfiguration(this._output,t),this._technique),this._technique}ensureResources(e){return n.Iv.LOADED}}class l extends s{constructor(e){super(e),this._numLoading=0,this._disposed=!1,this._textureRepository=e.textureRepository,this._textureId=e.textureId,this._acquire(e.textureId,(e=>this._texture=e)),this._acquire(e.normalTextureId,(e=>this._textureNormal=e)),this._acquire(e.emissiveTextureId,(e=>this._textureEmissive=e)),this._acquire(e.occlusionTextureId,(e=>this._textureOcclusion=e)),this._acquire(e.metallicRoughnessTextureId,(e=>this._textureMetallicRoughness=e))}dispose(){this._texture=(0,i.gT)(this._texture),this._textureNormal=(0,i.gT)(this._textureNormal),this._textureEmissive=(0,i.gT)(this._textureEmissive),this._textureOcclusion=(0,i.gT)(this._textureOcclusion),this._textureMetallicRoughness=(0,i.gT)(this._textureMetallicRoughness),this._disposed=!0}ensureResources(e){return 0===this._numLoading?n.Iv.LOADED:n.Iv.LOADING}get textureBindParameters(){return new c(null!=this._texture?this._texture.glTexture:null,null!=this._textureNormal?this._textureNormal.glTexture:null,null!=this._textureEmissive?this._textureEmissive.glTexture:null,null!=this._textureOcclusion?this._textureOcclusion.glTexture:null,null!=this._textureMetallicRoughness?this._textureMetallicRoughness.glTexture:null)}updateTexture(e){null!=this._texture&&e===this._texture.id||(this._texture=(0,i.gT)(this._texture),this._textureId=e,this._acquire(this._textureId,(e=>this._texture=e)))}_acquire(e,t){if(null==e)return void t(null);const r=this._textureRepository.acquire(e);if((0,o.eW)(r))return++this._numLoading,void r.then((e=>{if(this._disposed)return(0,i.gT)(e),void t(null);t(e)})).finally((()=>--this._numLoading));t(r)}}class c extends a.a{constructor(e=null,t=null,r=null,i=null,o=null,a,n){super(),this.texture=e,this.textureNormal=t,this.textureEmissive=r,this.textureOcclusion=i,this.textureMetallicRoughness=o,this.scale=a,this.normalTextureTransformMatrix=n}}},81048:(e,t,r)=>{r.d(t,{iK:()=>h,C2:()=>i});var i,o,a=r(62484),n=r(60288),s=r(12260),l=r(58608),c=r(74376),d=r(63524),u=r(22592);class h extends l.u{constructor(e,t){super(),this.type=c.C.Material,this.supportsEdges=!1,this._visible=!0,this._renderPriority=0,this._vertexAttributeLocations=d.q,this._pp0=(0,a.WK)(0,0,1),this._pp1=(0,a.WK)(0,0,0),this._parameters=(0,u.KM)(e,t),this.validateParameters(this._parameters)}get parameters(){return this._parameters}update(e){return!1}setParameters(e,t=!0){(0,u.S_)(this._parameters,e)&&(this.validateParameters(this._parameters),t&&this.parametersChanged())}validateParameters(e){}get visible(){return this._visible}set visible(e){e!==this._visible&&(this._visible=e,this.parametersChanged())}shouldRender(e){return this.isVisible()&&this.isVisibleForOutput(e.output)&&(!this.parameters.isDecoration||e.bindParameters.decorations===s.C2.ON)&&0!=(this.parameters.renderOccluded&e.renderOccludedMask)}isVisibleForOutput(e){return!0}get renderPriority(){return this._renderPriority}set renderPriority(e){e!==this._renderPriority&&(this._renderPriority=e,this.parametersChanged())}get vertexAttributeLocations(){return this._vertexAttributeLocations}isVisible(){return this._visible}parametersChanged(){this.repository?.materialChanged(this)}queryRenderOccludedState(e){return this.isVisible()&&this.parameters.renderOccluded===e}intersectDraped(e,t,r,i,o,a){return this._pp0[0]=this._pp1[0]=i[0],this._pp0[1]=this._pp1[1]=i[1],this.intersect(e,t,r,this._pp0,this._pp1,o)}}(o=i||(i={}))[o.None=0]="None",o[o.Occlude=1]="Occlude",o[o.Transparent=2]="Transparent",o[o.OccludeAndTransparent=4]="OccludeAndTransparent",o[o.OccludeAndTransparentStencil=8]="OccludeAndTransparentStencil",o[o.Opaque=16]="Opaque",n.a},1492:(e,t,r)=>{r.d(t,{G:()=>a});var i=r(24680),o=r(94460);class a{constructor(e,t,r){this._context=e,this._locations=r,this._textures=new Map,this._freeTextureUnits=new i.c({deallocator:null}),this._glProgram=e.programCache.acquire(t.generate("vertex"),t.generate("fragment"),r),this._glProgram.stop=()=>{throw new Error("Wrapped _glProgram used directly")},this.bindPass=t.generateBindPass(this),this.bindDraw=t.generateBindDraw(this),this._fragmentUniforms=(0,o.gv)()?t.fragmentUniforms:null}dispose(){this._glProgram.dispose()}get glName(){return this._glProgram.glName}get hasTransformFeedbackVaryings(){return this._glProgram.hasTransformFeedbackVaryings}get compiled(){return this._glProgram.compiled}setUniform1b(e,t){this._glProgram.setUniform1i(e,t?1:0)}setUniform1i(e,t){this._glProgram.setUniform1i(e,t)}setUniform1f(e,t){this._glProgram.setUniform1f(e,t)}setUniform2fv(e,t){this._glProgram.setUniform2fv(e,t)}setUniform3fv(e,t){this._glProgram.setUniform3fv(e,t)}setUniform4fv(e,t){this._glProgram.setUniform4fv(e,t)}setUniformMatrix3fv(e,t){this._glProgram.setUniformMatrix3fv(e,t)}setUniformMatrix4fv(e,t){this._glProgram.setUniformMatrix4fv(e,t)}setUniform1fv(e,t){this._glProgram.setUniform1fv(e,t)}setUniform1iv(e,t){this._glProgram.setUniform1iv(e,t)}setUniform2iv(e,t){this._glProgram.setUniform3iv(e,t)}setUniform3iv(e,t){this._glProgram.setUniform3iv(e,t)}setUniform4iv(e,t){this._glProgram.setUniform4iv(e,t)}assertCompatibleVertexAttributeLocations(e){e.locations!==this._locations&&console.error("VertexAttributeLocations are incompatible")}stop(){this._textures.clear(),this._freeTextureUnits.clear()}bindTexture(e,t){if(null==t?.glName){const t=this._textures.get(e);return t&&(this._context.bindTexture(null,t.unit),this._freeTextureUnit(t),this._textures.delete(e)),null}let r=this._textures.get(e);return null==r?(r=this._allocTextureUnit(t),this._textures.set(e,r)):r.texture=t,this._context.useProgram(this),this.setUniform1i(e,r.unit),this._context.bindTexture(t,r.unit),r.unit}rebindTextures(){this._context.useProgram(this),this._textures.forEach(((e,t)=>{this._context.bindTexture(e.texture,e.unit),this.setUniform1i(t,e.unit)})),this._fragmentUniforms?.forEach((e=>{"sampler2D"!==e.type&&"samplerCube"!==e.type||this._textures.has(e.name)||console.error(`Texture sampler ${e.name} has no bound texture`)}))}_allocTextureUnit(e){return{texture:e,unit:0===this._freeTextureUnits.length?this._textures.size:this._freeTextureUnits.pop()}}_freeTextureUnit(e){this._freeTextureUnits.push(e.unit)}}},40328:(e,t,r)=>{var i;r.d(t,{y:()=>i}),function(e){e[e.INTEGRATED_MESH=0]="INTEGRATED_MESH",e[e.OPAQUE_TERRAIN=1]="OPAQUE_TERRAIN",e[e.OPAQUE_MATERIAL=2]="OPAQUE_MATERIAL",e[e.OPAQUE_NO_SSAO_DEPTH=3]="OPAQUE_NO_SSAO_DEPTH",e[e.TRANSPARENT_MATERIAL=4]="TRANSPARENT_MATERIAL",e[e.TRANSPARENT_NO_SSAO_DEPTH=5]="TRANSPARENT_NO_SSAO_DEPTH",e[e.TRANSPARENT_TERRAIN=6]="TRANSPARENT_TERRAIN",e[e.TRANSPARENT_DEPTH_WRITE_DISABLED_MATERIAL=7]="TRANSPARENT_DEPTH_WRITE_DISABLED_MATERIAL",e[e.OCCLUDED_TERRAIN=8]="OCCLUDED_TERRAIN",e[e.OCCLUDER_MATERIAL=9]="OCCLUDER_MATERIAL",e[e.TRANSPARENT_OCCLUDER_MATERIAL=10]="TRANSPARENT_OCCLUDER_MATERIAL",e[e.OCCLUSION_PIXELS=11]="OCCLUSION_PIXELS",e[e.ANTIALIASING=12]="ANTIALIASING",e[e.COMPOSITE=13]="COMPOSITE",e[e.BLIT=14]="BLIT",e[e.SSAO=15]="SSAO",e[e.HIGHLIGHT=16]="HIGHLIGHT",e[e.SHADOW_HIGHLIGHT=17]="SHADOW_HIGHLIGHT",e[e.ENVIRONMENT_OPAQUE=18]="ENVIRONMENT_OPAQUE",e[e.ENVIRONMENT_TRANSPARENT=19]="ENVIRONMENT_TRANSPARENT",e[e.LASERLINES=20]="LASERLINES",e[e.LASERLINES_CONTRAST_CONTROL=21]="LASERLINES_CONTRAST_CONTROL",e[e.HUD_MATERIAL=22]="HUD_MATERIAL",e[e.LABEL_MATERIAL=23]="LABEL_MATERIAL",e[e.LINE_CALLOUTS=24]="LINE_CALLOUTS",e[e.LINE_CALLOUTS_HUD_DEPTH=25]="LINE_CALLOUTS_HUD_DEPTH",e[e.DRAPED_MATERIAL=26]="DRAPED_MATERIAL",e[e.DRAPED_WATER=27]="DRAPED_WATER",e[e.VOXEL=28]="VOXEL",e[e.MAX_SLOTS=29]="MAX_SLOTS"}(i||(i={}))},56336:(e,t,r)=>{var i;r.d(t,{H:()=>i}),function(e){e[e.Color=0]="Color",e[e.Alpha=1]="Alpha",e[e.FrontFace=2]="FrontFace",e[e.NONE=3]="NONE",e[e.COUNT=4]="COUNT"}(i||(i={}))},22592:(e,t,r)=>{r.d(t,{An:()=>C,KM:()=>S,Ep:()=>f,S_:()=>A,u6:()=>b});var i=r(92400),o=r(53648),a=r(98472),n=r(62484),s=r(77048),l=r(74376);function c(e,t,r,i){return function(e,t){return(0,o.Cq)(e*Math.max(t.scale,t.minScaleFactor),e,t.factor)}(e,function(e,t,r){const i=r.parameters;return d.scale=Math.min(i.divisor/(t-i.offset),1),d.factor=function(e){return Math.abs(e*e*e)}(e),d}(t,r,i))}r(21288),(0,o.EF)(10),(0,o.EF)(12),(0,o.EF)(70),(0,o.EF)(40);const d={scale:0,factor:0,minScaleFactor:0};var u=r(90736),h=r(4636);const m=(0,s.Su)();function f(e,t,r,i,o,a){if(e.visible)if(e.boundingInfo){(0,u.Uc)(e.type===l.C.Mesh);const n=t.tolerance;v(e.boundingInfo,r,i,n,o,a)}else{const t=e.attributes.get(h.K.POSITION),n=t.indices;_(r,i,0,n.length/3,n,t,void 0,o,a)}}const p=(0,n.Su)();function v(e,t,r,i,o,n){if(null==e)return;const l=function(e,t,r){return(0,a.s)(r,1/(t[0]-e[0]),1/(t[1]-e[1]),1/(t[2]-e[2]))}(t,r,p);if((0,s.C6)(m,e.bbMin),(0,s.mK)(m,e.bbMax),null!=o&&o.applyToAabb(m),function(e,t,r,i){return function(e,t,r,i,o){const a=(e[0]-i-t[0])*r[0],n=(e[3]+i-t[0])*r[0];let s=Math.min(a,n),l=Math.max(a,n);const c=(e[1]-i-t[1])*r[1],d=(e[4]+i-t[1])*r[1];if(l=Math.min(l,Math.max(c,d)),l<0)return!1;if(s=Math.max(s,Math.min(c,d)),s>l)return!1;const u=(e[2]-i-t[2])*r[2],h=(e[5]+i-t[2])*r[2];return l=Math.min(l,Math.max(u,h)),!(l<0)&&(s=Math.max(s,Math.min(u,h)),!(s>l)&&s<1/0)}(e,t,r,i)}(m,t,l,i)){const{primitiveIndices:a,position:s}=e,l=a?a.length:s.indices.length/3;if(l>w){const a=e.getChildren();if(void 0!==a){for(const e of a)v(e,t,r,i,o,n);return}}_(t,r,0,l,s.indices,s,a,o,n)}}const g=(0,n.Su)();function _(e,t,r,i,o,a,n,s,l){if(n)return function(e,t,r,i,o,a,n,s,l){const{data:c,stride:d}=a,u=e[0],h=e[1],m=e[2],f=t[0]-u,p=t[1]-h,v=t[2]-m;for(let e=r;e<i;++e){const t=n[e];let r=3*t,i=d*o[r++],a=c[i++],_=c[i++],x=c[i];i=d*o[r++];let T=c[i++],b=c[i++],S=c[i];i=d*o[r];let A=c[i++],y=c[i++],C=c[i];null!=s&&([a,_,x]=s.applyToVertex(a,_,x,e),[T,b,S]=s.applyToVertex(T,b,S,e),[A,y,C]=s.applyToVertex(A,y,C,e));const w=T-a,M=b-_,R=S-x,I=A-a,O=y-_,N=C-x,P=p*N-O*v,L=v*I-N*f,D=f*O-I*p,F=w*P+M*L+R*D;if(Math.abs(F)<=Number.EPSILON)continue;const U=u-a,G=h-_,B=m-x,V=U*P+G*L+B*D;if(F>0){if(V<0||V>F)continue}else if(V>0||V<F)continue;const z=G*R-M*B,Y=B*w-R*U,H=U*M-w*G,W=f*z+p*Y+v*H;if(F>0){if(W<0||V+W>F)continue}else if(W>0||V+W<F)continue;const j=(I*z+O*Y+N*H)/F;j>=0&&l(j,E(w,M,R,I,O,N,g),t,!1)}}(e,t,r,i,o,a,n,s,l);const{data:c,stride:d}=a,u=e[0],h=e[1],m=e[2],f=t[0]-u,p=t[1]-h,v=t[2]-m;for(let e=r,t=3*r;e<i;++e){let r=d*o[t++],i=c[r++],a=c[r++],n=c[r];r=d*o[t++];let _=c[r++],x=c[r++],T=c[r];r=d*o[t++];let b=c[r++],S=c[r++],A=c[r];null!=s&&([i,a,n]=s.applyToVertex(i,a,n,e),[_,x,T]=s.applyToVertex(_,x,T,e),[b,S,A]=s.applyToVertex(b,S,A,e));const y=_-i,C=x-a,w=T-n,M=b-i,R=S-a,I=A-n,O=p*I-R*v,N=v*M-I*f,P=f*R-M*p,L=y*O+C*N+w*P;if(Math.abs(L)<=Number.EPSILON)continue;const D=u-i,F=h-a,U=m-n,G=D*O+F*N+U*P;if(L>0){if(G<0||G>L)continue}else if(G>0||G<L)continue;const B=F*w-C*U,V=U*y-w*D,z=D*C-y*F,Y=f*B+p*V+v*z;if(L>0){if(Y<0||G+Y>L)continue}else if(Y>0||G+Y<L)continue;const H=(M*B+R*V+I*z)/L;H>=0&&l(H,E(y,C,w,M,R,I,g),e,!1)}}const x=(0,n.Su)(),T=(0,n.Su)();function E(e,t,r,i,o,n,s){return(0,a.s)(x,e,t,r),(0,a.s)(T,i,o,n),(0,a.b)(s,x,T),(0,a.n)(s,s),s}function b(e,t,r,i,a){let n=(r.screenLength||0)*e.pixelRatio;null!=a&&(n=c(n,i,t,a));const s=n*Math.tan(.5*e.fovY)/(.5*e.fullHeight);return(0,o.qk)(s*t,r.minWorldLength||0,null!=r.maxWorldLength?r.maxWorldLength:1/0)}function S(e,t){const r=t?S(t):{};for(const t in e){let i=e[t];i?.forEach&&(i=y(i)),null==i&&t in r||(r[t]=i)}return r}function A(e,t){let r=!1;for(const o in t){const a=t[o];void 0!==a&&(Array.isArray(a)?null===e[o]?(e[o]=a.slice(),r=!0):(0,i.Jr)(e[o],a)&&(r=!0):e[o]!==a&&(r=!0,e[o]=a))}return r}function y(e){const t=[];return e.forEach((e=>t.push(e))),t}const C={multiply:1,ignore:2,replace:3,tint:4},w=1e3},21288:(e,t,r)=>{var i;r.d(t,{UZ:()=>i}),function(e){e[e.Global=1]="Global",e[e.Local=2]="Local"}(i||(i={}))},27308:(e,t,r)=>{var i;r.d(t,{K:()=>i}),function(e){e[e.ANIMATING=0]="ANIMATING",e[e.INTERACTING=1]="INTERACTING",e[e.IDLE=2]="IDLE"}(i||(i={}))},39104:(e,t,r)=>{var i;r.d(t,{A:()=>i}),function(e){e[e.Texture=0]="Texture",e[e.RenderBuffer=1]="RenderBuffer"}(i||(i={}))},22184:(e,t,r)=>{r.d(t,{w:()=>c});var i=r(20744),o=(r(9456),r(94460)),a=r(77800),n=r(39104),s=r(51576);class l extends s.O{constructor(e,t){switch(super(),this.context=e,Object.assign(this,t),this.internalFormat){case a.UV.R16F:case a.UV.R16I:case a.UV.R16UI:case a.UV.R32F:case a.UV.R32I:case a.UV.R32UI:case a.UV.R8_SNORM:case a.UV.R8:case a.UV.R8I:case a.UV.R8UI:this.pixelFormat=a.Id.RED}}static validate(e,t){return new l(e,t)}}let c=class e{constructor(e,t=null,r=null){if(this.type=n.A.Texture,this._glName=null,this._samplingModeDirty=!1,this._wrapModeDirty=!1,this._wasImmutablyAllocated=!1,"context"in e)this._descriptor=e,r=t;else{const r=l.validate(e,t);if(!r)throw new i.c("Texture descriptor invalid");this._descriptor=r}this._descriptor.target===a.c5.TEXTURE_CUBE_MAP?this._setDataCubeMap(r):this.setData(r)}get glName(){return this._glName}get descriptor(){return this._descriptor}get usedMemory(){return(0,s.k)(this._descriptor)}get isDirty(){return this._samplingModeDirty||this._wrapModeDirty}dispose(){this._glName&&this._descriptor.context.instanceCounter.decrement(a.y4.Texture,this),this._descriptor.context.gl&&this._glName&&(this._descriptor.context.unbindTexture(this),this._descriptor.context.gl.deleteTexture(this._glName),this._glName=null)}release(){this.dispose()}resize(e,t){const r=this._descriptor;if(r.width!==e||r.height!==t){if(this._wasImmutablyAllocated)throw new i.c("Immutable textures can't be resized!");r.width=e,r.height=t,this._descriptor.target===a.c5.TEXTURE_CUBE_MAP?this._setDataCubeMap(null):this.setData(null)}}_setDataCubeMap(e=null){for(let t=a.c5.TEXTURE_CUBE_MAP_POSITIVE_X;t<=a.c5.TEXTURE_CUBE_MAP_NEGATIVE_Z;t++)this._setData(e,t)}setData(e){this._setData(e)}_setData(t,r){if(!this._descriptor.context?.gl)return;const n=this._descriptor.context.gl;(0,o.m_)(n),this._glName||(this._glName=n.createTexture(),this._glName&&this._descriptor.context.instanceCounter.increment(a.y4.Texture,this)),void 0===t&&(t=null);const s=this._descriptor,l=r??s.target,c=p(l);null===t&&(s.width=s.width||4,s.height=s.height||4,c&&(s.depth=s.depth??1));const g=this._descriptor.context.bindTexture(this,e.TEXTURE_UNIT_FOR_UPDATES);this._descriptor.context.setActiveTexture(e.TEXTURE_UNIT_FOR_UPDATES),d(s),this._configurePixelStorage(),(0,o.m_)(n);const _=this._deriveInternalFormat();if(f(t)){let e="width"in t?t.width:t.codedWidth,r="height"in t?t.height:t.codedHeight;const i=1;t instanceof HTMLVideoElement&&(e=t.videoWidth,r=t.videoHeight),s.width&&s.height,c&&s.depth,s.isImmutable&&!this._wasImmutablyAllocated&&this._texStorage(l,_,s.hasMipmap,e,r,i),this._texImage(l,0,_,e,r,i,t),(0,o.m_)(n),s.hasMipmap&&this.generateMipmap(),s.width||(s.width=e),s.height||(s.height=r),c&&!s.depth&&(s.depth=i)}else{const{width:e,height:r,depth:d}=s;if(null==e||null==r)throw new i.c("Width and height must be specified!");if(c&&null==d)throw new i.c("Depth must be specified!");if(s.isImmutable&&!this._wasImmutablyAllocated&&this._texStorage(l,_,s.hasMipmap,e,r,d),m(t)){const o=t.levels,c=v(l,e,r,d),u=Math.min(c-1,o.length-1);n.texParameteri(s.target,this._descriptor.context.gl.TEXTURE_MAX_LEVEL,u);const h=_;if(!(h in a.sr))throw new i.c("Attempting to use compressed data with an uncompressed format!");this._forEachMipmapLevel(((e,t,r,i)=>{const a=o[Math.min(e,o.length-1)];this._compressedTexImage(l,e,h,t,r,i,a)}),u)}else this._texImage(l,0,_,e,r,d,t),(0,o.m_)(n),s.hasMipmap&&this.generateMipmap()}u(n,this._descriptor),h(n,this._descriptor),function(e,t){const r=e.capabilities.textureFilterAnisotropic;r&&e.gl.texParameterf(t.target,r.TEXTURE_MAX_ANISOTROPY,t.maxAnisotropy??1)}(this._descriptor.context,this._descriptor),(0,o.m_)(n),this._descriptor.context.bindTexture(g,e.TEXTURE_UNIT_FOR_UPDATES)}updateData(t,r,o,a,n,s,l=0){s||console.error("An attempt to use uninitialized data!"),this._glName||console.error("An attempt to update uninitialized texture!");const c=this._descriptor,d=this._deriveInternalFormat(),{context:u,pixelFormat:h,dataType:p,target:v,isImmutable:g}=c;if(g&&!this._wasImmutablyAllocated)throw new i.c("Cannot update immutable texture before allocation!");const _=u.bindTexture(this,e.TEXTURE_UNIT_FOR_UPDATES,!0);(r<0||o<0||a>c.width||n>c.height||r+a>c.width||o+n>c.height)&&console.error("An attempt to update out of bounds of the texture!"),this._configurePixelStorage();const{gl:x}=u;l&&x.pixelStorei(x.UNPACK_SKIP_ROWS,l),f(s)?x.texSubImage2D(v,t,r,o,a,n,h,p,s):m(s)?x.compressedTexSubImage2D(v,t,r,o,a,n,d,s.levels[t]):x.texSubImage2D(v,t,r,o,a,n,h,p,s),l&&x.pixelStorei(x.UNPACK_SKIP_ROWS,0),u.bindTexture(_,e.TEXTURE_UNIT_FOR_UPDATES)}updateData3D(t,r,o,a,n,s,l,c){c||console.error("An attempt to use uninitialized data!"),this._glName||console.error("An attempt to update uninitialized texture!");const d=this._descriptor,u=this._deriveInternalFormat(),{context:h,pixelFormat:f,dataType:v,isImmutable:g,target:_}=d;if(g&&!this._wasImmutablyAllocated)throw new i.c("Cannot update immutable texture before allocation!");p(_)||console.warn("Attempting to set 3D texture data on a non-3D texture");const x=h.bindTexture(this,e.TEXTURE_UNIT_FOR_UPDATES);h.setActiveTexture(e.TEXTURE_UNIT_FOR_UPDATES),(r<0||o<0||a<0||n>d.width||s>d.height||l>d.depth||r+n>d.width||o+s>d.height||a+l>d.depth)&&console.error("An attempt to update out of bounds of the texture!"),this._configurePixelStorage();const{gl:T}=h;if(m(c))c=c.levels[t],T.compressedTexSubImage3D(_,t,r,o,a,n,s,l,u,c);else{const e=c;T.texSubImage3D(_,t,r,o,a,n,s,l,f,v,e)}h.bindTexture(x,e.TEXTURE_UNIT_FOR_UPDATES)}generateMipmap(){const t=this._descriptor;if(!t.hasMipmap){if(this._wasImmutablyAllocated)throw new i.c("Cannot add mipmaps to immutable texture after allocation");t.hasMipmap=!0,this._samplingModeDirty=!0,d(t)}t.samplingMode===a.Ud.LINEAR?(this._samplingModeDirty=!0,t.samplingMode=a.Ud.LINEAR_MIPMAP_NEAREST):t.samplingMode===a.Ud.NEAREST&&(this._samplingModeDirty=!0,t.samplingMode=a.Ud.NEAREST_MIPMAP_NEAREST);const r=this._descriptor.context.bindTexture(this,e.TEXTURE_UNIT_FOR_UPDATES);this._descriptor.context.setActiveTexture(e.TEXTURE_UNIT_FOR_UPDATES),this._descriptor.context.gl.generateMipmap(t.target),this._descriptor.context.bindTexture(r,e.TEXTURE_UNIT_FOR_UPDATES)}setSamplingMode(e){e!==this._descriptor.samplingMode&&(this._descriptor.samplingMode=e,this._samplingModeDirty=!0)}setWrapMode(e){e!==this._descriptor.wrapMode&&(this._descriptor.wrapMode=e,d(this._descriptor),this._wrapModeDirty=!0)}applyChanges(){const e=this._descriptor,t=e.context.gl;this._samplingModeDirty&&(u(t,e),this._samplingModeDirty=!1),this._wrapModeDirty&&(h(t,e),this._wrapModeDirty=!1)}_deriveInternalFormat(){if(null!=this._descriptor.internalFormat)return this._descriptor.internalFormat===a.Id.DEPTH_STENCIL&&(this._descriptor.internalFormat=a.Id.DEPTH24_STENCIL8),this._descriptor.internalFormat;switch(this._descriptor.dataType){case a.OK.FLOAT:switch(this._descriptor.pixelFormat){case a.Id.RGBA:return this._descriptor.internalFormat=a.UV.RGBA32F;case a.Id.RGB:return this._descriptor.internalFormat=a.UV.RGB32F;default:throw new i.c("Unable to derive format")}case a.OK.UNSIGNED_BYTE:switch(this._descriptor.pixelFormat){case a.Id.RGBA:return this._descriptor.internalFormat=a.UV.RGBA8;case a.Id.RGB:return this._descriptor.internalFormat=a.UV.RGB8}}return this._descriptor.internalFormat=this._descriptor.pixelFormat===a.Id.DEPTH_STENCIL?a.Id.DEPTH24_STENCIL8:this._descriptor.pixelFormat}_configurePixelStorage(){const e=this._descriptor.context.gl,{unpackAlignment:t,flipped:r,preMultiplyAlpha:i}=this._descriptor;e.pixelStorei(e.UNPACK_ALIGNMENT,t),e.pixelStorei(e.UNPACK_FLIP_Y_WEBGL,r?1:0),e.pixelStorei(e.UNPACK_PREMULTIPLY_ALPHA_WEBGL,i?1:0)}_texStorage(e,t,r,o,n,s){const{gl:l}=this._descriptor.context;if(!(t in a.UV))throw new i.c("Immutable textures must have a sized internal format");if(!this._descriptor.isImmutable)return;const c=r?v(e,o,n,s):1;if(p(e)){if(null==s)throw new i.c("Missing depth dimension for 3D texture upload");l.texStorage3D(e,c,t,o,n,s)}else l.texStorage2D(e,c,t,o,n);this._wasImmutablyAllocated=!0}_texImage(e,t,r,o,a,n,s){const l=this._descriptor.context.gl,c=p(e),{isImmutable:d,pixelFormat:u,dataType:h}=this._descriptor;if(d){if(null!=s){const r=s;if(c){if(null==n)throw new i.c("Missing depth dimension for 3D texture upload");l.texSubImage3D(e,t,0,0,0,o,a,n,u,h,r)}else l.texSubImage2D(e,t,0,0,o,a,u,h,r)}}else{const d=s;if(c){if(null==n)throw new i.c("Missing depth dimension for 3D texture upload");l.texImage3D(e,t,r,o,a,n,0,u,h,d)}else l.texImage2D(e,t,r,o,a,0,u,h,d)}}_compressedTexImage(e,t,r,o,a,n,s){const l=this._descriptor.context.gl,c=p(e);if(this._descriptor.isImmutable){if(null!=s)if(c){if(null==n)throw new i.c("Missing depth dimension for 3D texture upload");l.compressedTexSubImage3D(e,t,0,0,0,o,a,n,r,s)}else l.compressedTexSubImage2D(e,t,0,0,o,a,r,s)}else if(c){if(null==n)throw new i.c("Missing depth dimension for 3D texture upload");l.compressedTexImage3D(e,t,r,o,a,n,0,s)}else l.compressedTexImage2D(e,t,r,o,a,0,s)}_forEachMipmapLevel(e,t=1/0){let{width:r,height:o,depth:n,hasMipmap:s,target:l}=this._descriptor;const c=l===a.c5.TEXTURE_3D;if(null==r||null==o||c&&null==n)throw new i.c("Missing texture dimensions for mipmap calculation");for(let i=0;e(i,r,o,n),s&&(1!==r||1!==o||c&&1!==n)&&!(i>=t);++i)r=Math.max(1,r>>1),o=Math.max(1,o>>1),c&&(n=Math.max(1,n>>1))}};function d(e){(null!=e.width&&e.width<0||null!=e.height&&e.height<0||null!=e.depth&&e.depth<0)&&console.error("Negative dimension parameters are not allowed!")}function u(e,t){let r=t.samplingMode,i=t.samplingMode;r===a.Ud.LINEAR_MIPMAP_NEAREST||r===a.Ud.LINEAR_MIPMAP_LINEAR?(r=a.Ud.LINEAR,t.hasMipmap||(i=a.Ud.LINEAR)):r!==a.Ud.NEAREST_MIPMAP_NEAREST&&r!==a.Ud.NEAREST_MIPMAP_LINEAR||(r=a.Ud.NEAREST,t.hasMipmap||(i=a.Ud.NEAREST)),e.texParameteri(t.target,e.TEXTURE_MAG_FILTER,r),e.texParameteri(t.target,e.TEXTURE_MIN_FILTER,i)}function h(e,t){"number"==typeof t.wrapMode?(e.texParameteri(t.target,e.TEXTURE_WRAP_S,t.wrapMode),e.texParameteri(t.target,e.TEXTURE_WRAP_T,t.wrapMode)):(e.texParameteri(t.target,e.TEXTURE_WRAP_S,t.wrapMode.s),e.texParameteri(t.target,e.TEXTURE_WRAP_T,t.wrapMode.t))}function m(e){return null!=e&&"type"in e&&"compressed"===e.type}function f(e){return null!=e&&!m(e)&&!function(e){return null!=e&&"byteLength"in e}(e)}function p(e){return e===a.c5.TEXTURE_3D||e===a.c5.TEXTURE_2D_ARRAY}function v(e,t,r,i=1){let o=Math.max(t,r);return e===a.c5.TEXTURE_3D&&(o=Math.max(o,i)),Math.round(Math.log(o)/Math.LN2)+1}c.TEXTURE_UNIT_FOR_UPDATES=0},51576:(e,t,r)=>{r.d(t,{O:()=>a,k:()=>n});var i=r(77800),o=r(97684);class a{constructor(e=0,t=e){this.width=e,this.height=t,this.target=i.c5.TEXTURE_2D,this.pixelFormat=i.Id.RGBA,this.dataType=i.OK.UNSIGNED_BYTE,this.samplingMode=i.Ud.LINEAR,this.wrapMode=i.Qz.REPEAT,this.maxAnisotropy=1,this.flipped=!1,this.hasMipmap=!1,this.isOpaque=!1,this.unpackAlignment=4,this.preMultiplyAlpha=!1,this.depth=1,this.isImmutable=!1}}function n(e){return e.width<=0||e.height<=0?0:Math.round(e.width*e.height*(e.hasMipmap?4/3:1)*(null==e.internalFormat?4:(0,o.O4)(e.internalFormat)))}},97684:(e,t,r)=>{r.d(t,{O4:()=>o}),r(9456),r(94460);var i=r(77800);function o(e){switch(e){case i.Id.ALPHA:case i.Id.LUMINANCE:case i.Id.RED:case i.Id.RED_INTEGER:case i.UV.R8:case i.UV.R8I:case i.UV.R8UI:case i.UV.R8_SNORM:case i.Qb.STENCIL_INDEX8:return 1;case i.Id.LUMINANCE_ALPHA:case i.Id.RG:case i.Id.RG_INTEGER:case i.UV.RGBA4:case i.UV.R16F:case i.UV.R16I:case i.UV.R16UI:case i.UV.RG8:case i.UV.RG8I:case i.UV.RG8UI:case i.UV.RG8_SNORM:case i.UV.RGB565:case i.UV.RGB5_A1:case i.Qb.DEPTH_COMPONENT16:return 2;case i.Id.DEPTH_COMPONENT:case i.Id.RGB:case i.Id.RGB_INTEGER:case i.UV.RGB8:case i.UV.RGB8I:case i.UV.RGB8UI:case i.UV.RGB8_SNORM:case i.UV.SRGB8:case i.Qb.DEPTH_COMPONENT24:return 3;case i.Id.DEPTH_STENCIL:case i.Id.DEPTH24_STENCIL8:case i.Id.RGBA:case i.Id.RGBA_INTEGER:case i.UV.RGBA8:case i.UV.R32F:case i.UV.R11F_G11F_B10F:case i.UV.RG16F:case i.UV.R32I:case i.UV.R32UI:case i.UV.RG16I:case i.UV.RG16UI:case i.UV.RGBA8I:case i.UV.RGBA8UI:case i.UV.RGBA8_SNORM:case i.UV.SRGB8_ALPHA8:case i.UV.RGB9_E5:case i.UV.RGB10_A2UI:case i.UV.RGB10_A2:case i.Qb.DEPTH_STENCIL:case i.Qb.DEPTH_COMPONENT32F:case i.Qb.DEPTH24_STENCIL8:return 4;case i.Qb.DEPTH32F_STENCIL8:return 5;case i.UV.RGB16F:case i.UV.RGB16I:case i.UV.RGB16UI:return 6;case i.UV.RG32F:case i.UV.RG32I:case i.UV.RG32UI:case i.UV.RGBA16F:case i.UV.RGBA16I:case i.UV.RGBA16UI:return 8;case i.UV.RGB32F:case i.UV.RGB32I:case i.UV.RGB32UI:return 12;case i.UV.RGBA32F:case i.UV.RGBA32I:case i.UV.RGBA32UI:return 16;case i.sr.COMPRESSED_RGB_S3TC_DXT1_EXT:case i.sr.COMPRESSED_RGBA_S3TC_DXT1_EXT:return.5;case i.sr.COMPRESSED_RGBA_S3TC_DXT3_EXT:case i.sr.COMPRESSED_RGBA_S3TC_DXT5_EXT:return 1;case i.sr.COMPRESSED_R11_EAC:case i.sr.COMPRESSED_SIGNED_R11_EAC:case i.sr.COMPRESSED_RGB8_ETC2:case i.sr.COMPRESSED_SRGB8_ETC2:case i.sr.COMPRESSED_RGB8_PUNCHTHROUGH_ALPHA1_ETC2:case i.sr.COMPRESSED_SRGB8_PUNCHTHROUGH_ALPHA1_ETC2:return.5;case i.sr.COMPRESSED_RG11_EAC:case i.sr.COMPRESSED_SIGNED_RG11_EAC:case i.sr.COMPRESSED_RGBA8_ETC2_EAC:case i.sr.COMPRESSED_SRGB8_ALPHA8_ETC2_EAC:return 1}return 0}r(2944)},94460:(e,t,r)=>{r.d(t,{E5:()=>c,gv:()=>l,m_:()=>d});var i=r(20744),o=r(9456),a=r(66360);const n=()=>a.c.getLogger("geoscene.views.webgl.checkWebGLError"),s=!!(0,o.c)("enable-feature:webgl-debug");function l(){return s}function c(){return s}function d(e){if(l()){const t=e.getError();if(t){const r=function(e,t){switch(t){case e.INVALID_ENUM:return"Invalid Enum. An unacceptable value has been specified for an enumerated argument.";case e.INVALID_VALUE:return"Invalid Value. A numeric argument is out of range.";case e.INVALID_OPERATION:return"Invalid Operation. The specified command is not allowed for the current state.";case e.INVALID_FRAMEBUFFER_OPERATION:return"Invalid Framebuffer operation. The currently bound framebuffer is not framebuffer complete when trying to render to or to read from it.";case e.OUT_OF_MEMORY:return"Out of memory. Not enough memory is left to execute the command.";case e.CONTEXT_LOST_WEBGL:return"WebGL context has been lost";default:return"Unknown error"}}(e,t),o=(new Error).stack;n().error(new i.c("webgl-error","WebGL error occurred",{message:r,stack:o}))}}}},2944:(e,t,r)=>{r(77800)},81472:(e,t,r)=>{r.d(t,{AD:()=>c,AT:()=>d,KO:()=>a,_w:()=>E,ac:()=>n,kp:()=>u});var i=r(12260),o=r(77800);function a(e,t,r=o.YT.ADD,i=[0,0,0,0]){return{srcRgb:e,srcAlpha:e,dstRgb:t,dstAlpha:t,opRgb:r,opAlpha:r,color:{r:i[0],g:i[1],b:i[2],a:i[3]}}}function n(e,t,r,i,a=o.YT.ADD,n=o.YT.ADD,s=[0,0,0,0]){return{srcRgb:e,srcAlpha:t,dstRgb:r,dstAlpha:i,opRgb:a,opAlpha:n,color:{r:s[0],g:s[1],b:s[2],a:s[3]}}}const s={face:o.SC.BACK,mode:o.vs.CCW},l={face:o.SC.FRONT,mode:o.vs.CCW},c=e=>e===i.CM.Back?s:e===i.CM.Front?l:null,d={zNear:0,zFar:1},u={r:!0,g:!0,b:!0,a:!0};function h(e){return A.intern(e)}function m(e){return C.intern(e)}function f(e){return M.intern(e)}function p(e){return I.intern(e)}function v(e){return N.intern(e)}function g(e){return L.intern(e)}function _(e){return F.intern(e)}function x(e){return G.intern(e)}function T(e){return V.intern(e)}function E(e){return Y.intern(e)}class b{constructor(e,t){this._makeKey=e,this._makeRef=t,this._interns=new Map}intern(e){if(!e)return null;const t=this._makeKey(e),r=this._interns;return r.has(t)||r.set(t,this._makeRef(e)),r.get(t)??null}}function S(e){return"["+e.join(",")+"]"}const A=new b(y,(e=>({__tag:"Blending",...e})));function y(e){return e?S([e.srcRgb,e.srcAlpha,e.dstRgb,e.dstAlpha,e.opRgb,e.opAlpha,e.color.r,e.color.g,e.color.b,e.color.a]):null}const C=new b(w,(e=>({__tag:"Culling",...e})));function w(e){return e?S([e.face,e.mode]):null}const M=new b(R,(e=>({__tag:"PolygonOffset",...e})));function R(e){return e?S([e.factor,e.units]):null}const I=new b(O,(e=>({__tag:"DepthTest",...e})));function O(e){return e?S([e.func]):null}const N=new b(P,(e=>({__tag:"StencilTest",...e})));function P(e){return e?S([e.function.func,e.function.ref,e.function.mask,e.operation.fail,e.operation.zFail,e.operation.zPass]):null}const L=new b(D,(e=>({__tag:"DepthWrite",...e})));function D(e){return e?S([e.zNear,e.zFar]):null}const F=new b(U,(e=>({__tag:"ColorWrite",...e})));function U(e){return e?S([e.r,e.g,e.b,e.a]):null}const G=new b(B,(e=>({__tag:"StencilWrite",...e})));function B(e){return e?S([e.mask]):null}const V=new b(z,(e=>({__tag:"DrawBuffers",...e})));function z(e){return e?S(e.buffers):null}const Y=new b((function(e){return e?S([y(e.blending),w(e.culling),R(e.polygonOffset),O(e.depthTest),P(e.stencilTest),D(e.depthWrite),U(e.colorWrite),B(e.stencilWrite),z(e.drawBuffers)]):null}),(e=>({blending:h(e.blending),culling:m(e.culling),polygonOffset:f(e.polygonOffset),depthTest:p(e.depthTest),stencilTest:v(e.stencilTest),depthWrite:g(e.depthWrite),colorWrite:_(e.colorWrite),stencilWrite:x(e.stencilWrite),drawBuffers:T(e.drawBuffers)})))}}]);