/** Where docker-compose publishes the operational tools, relative to wherever the UI is served from. */
function onPort(port: number, path = ''): string {
  const { protocol, hostname } = window.location;
  return `${protocol}//${hostname}:${port}${path}`;
}

export const toolLinks = {
  swagger: () => onPort(8080, '/swagger-ui.html'),
  grafana: () => onPort(3001, '/d/order-saga'),
  jaeger: () => onPort(16686, '/search?service=order-service'),
  prometheus: () => onPort(9090, '/alerts'),
  eureka: () => onPort(8761),
  kafkaUi: () => onPort(8090),
};
