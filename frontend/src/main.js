// main.js
import { createApp } from 'vue';
// 必须最先加载：为 axios/fetch 注入登录令牌并统一处理 401
import './httpClient';
import App from './App.vue';
import "@arcgis/core/assets/esri/themes/light/main.css";
// 【新增】定义组件
import { defineCustomElements } from "@arcgis/map-components/dist/loader";

defineCustomElements(); // 激活 <arcgis-map> 等标签

createApp(App).mount('#app');