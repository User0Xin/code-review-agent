import { createRouter, createWebHistory } from 'vue-router'

export default createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    { path: '/', redirect: '/repositories' },
    {
      path: '/repositories',
      name: 'repositories',
      component: () => import('@/pages/RepositoryPage/index.vue'),
    },
    { path: '/reviews', name: 'reviews', component: () => import('@/pages/ReviewPage/index.vue') },
    { path: '/:pathMatch(.*)*', redirect: '/repositories' },
  ],
})
