export class XpbdSelectorObject {
  position: number;
  target: number;

  private previousPosition: number;
  private lambda = 0;

  constructor(initialPosition: number) {
    this.position = initialPosition;
    this.previousPosition = initialPosition;
    this.target = initialPosition;
  }

  setTarget(target: number) {
    this.target = target;
    this.lambda = 0;
  }

  jumpTo(target: number) {
    this.position = target;
    this.previousPosition = target;
    this.target = target;
    this.lambda = 0;
  }

  step(stepSeconds: number) {
    const velocity = (this.position - this.previousPosition) * 0.78;
    this.previousPosition = this.position;
    this.position += velocity;

    this.lambda = 0;
    const compliance = 0.00065;
    const alpha = compliance / (stepSeconds * stepSeconds);
    const constraintError = this.position - this.target;
    const deltaLambda = (-constraintError - alpha * this.lambda) / (1 + alpha);
    this.lambda += deltaLambda;
    this.position += deltaLambda;

    if (this.isSettled()) {
      this.jumpTo(this.target);
    }
    return this.position;
  }

  isSettled() {
    return Math.abs(this.position - this.target) < 0.001
      && Math.abs(this.position - this.previousPosition) < 0.001;
  }
}
