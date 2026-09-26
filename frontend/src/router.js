import { createRouter, createWebHistory } from 'vue-router'
import { auth } from './auth.js'

import HomeView from './views/HomeView.vue'
import WatchView from './views/WatchView.vue'
import UploadView from './views/UploadView.vue'
import LoginView from './views/LoginView.vue'
import RegisterView from './views/RegisterView.vue'
import MyView from './views/MyView.vue'
import AdminReviewView from './views/AdminReviewView.vue'
import ChannelView from './views/ChannelView.vue'
import UserHomeView from './views/UserHomeView.vue'
import NotFoundView from './views/NotFoundView.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', component: HomeView },
    { path: '/watch/:id', component: WatchView },
    { path: '/channel/:key', component: ChannelView },
    { path: '/uploader/:id', component: UserHomeView, props: true },
    { path: '/upload', component: UploadView, meta: { auth: true } },
    { path: '/login', component: LoginView },
    { path: '/register', component: RegisterView },
    { path: '/my', component: MyView, meta: { auth: true } },
    { path: '/admin/review', component: AdminReviewView, meta: { auth: true, admin: true } },
    // 通配 404 必须注册在所有具名路径之后,否则会吞掉前面的路由
    { path: '/:pathMatch(.*)*', component: NotFoundView }
  ]
})

router.beforeEach((to) => {
  if (to.meta.auth && !auth.user) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  if (to.meta.admin && auth.user?.role !== 'ADMIN') {
    return { path: '/' }
  }
})

export default router
