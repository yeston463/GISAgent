// main.js
import { createApp } from 'vue';
import App from './App.vue';
import "@arcgis/core/assets/esri/themes/light/main.css";
// 【新增】定义组件
import { defineCustomElements } from "@arcgis/map-components/dist/loader";

defineCustomElements(); // 激活 <arcgis-map> 等标签

createApp(App).mount('#app');